package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.human.protocol.pb.ControlMessages.ControlIntent;
import dev.kizuna.navigation.core.AStarPlanner;
import dev.kizuna.navigation.core.BodySize;
import dev.kizuna.navigation.core.BuiltInCapabilities;
import dev.kizuna.navigation.core.Cell;
import dev.kizuna.navigation.core.CollisionBox;
import dev.kizuna.navigation.core.DangerLayer;
import dev.kizuna.navigation.core.GridBounds;
import dev.kizuna.navigation.core.GridPos;
import dev.kizuna.navigation.core.Hazard;
import dev.kizuna.navigation.core.MoveKind;
import dev.kizuna.navigation.core.MotionPoint;
import dev.kizuna.navigation.core.MovementLimits;
import dev.kizuna.navigation.core.NavGoals;
import dev.kizuna.navigation.core.NavProfile;
import dev.kizuna.navigation.core.NavRequest;
import dev.kizuna.navigation.core.SearchBudget;
import dev.kizuna.navigation.core.Terrain;
import dev.kizuna.navigation.core.VoxelGrid;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** 26.2 玩家只算路的适配层；使用寻路核心，不安装其旧版本生物导航执行器。 */
final class HumanoidPlayerPath {
    private static final int HORIZONTAL_RANGE = 20;
    private static final int VERTICAL_RANGE = 4;
    private static final int SEARCH_BUDGET = 400;
    private static final int SAFE_DROP = 2;
    private static final double ARRIVAL_RADIUS = 2;
    private static final double WAYPOINT_RADIUS = 0.25;
    private static final int ROUTE_REFRESH_TICKS = 10;
    private static final AStarPlanner planner = new AStarPlanner(BuiltInCapabilities.all());
    private static final Map<ServerPlayer, Step> steps = new WeakHashMap<>();

    private HumanoidPlayerPath() {
        // 路径不执行传送、不扣功法费用，只产生原版可执行的行走与跳跃输入。
    }

    static void move(ServerPlayer player, Vec3 destination, BodySnapshot body, ControlIntent.Builder intent) {
        // 腿不能做功时停步；首版只走地面和一级普通跳，不把功法跃步当成免费移动。
        if (HumanoidFighters.legForceNewtons(body) <= 0) {
            return;
        }
        Step cached = steps.get(player);
        long tick = player.level().getServer().overworld().getGameTime();
        Step step = cached;
        if (player.onGround() && (cached == null || tick >= cached.refreshTick()
                || !cached.destination().equals(position(destination))
                || horizontalDistance(player.position(), cached.waypoint()) <= WAYPOINT_RADIUS)) {
            step = plan(player, destination, tick);
            if (step == null) {
                steps.remove(player);
            } else {
                steps.put(player, step);
            }
        }
        if (step != null) {
            steer(player, step, intent);
        }
    }

    private static Step plan(ServerPlayer player, Vec3 destination, long tick) {
        // 平地缓存短路径段；起跳后沿同一落点持续输入，不在空中反复求地面路径。
        GridPos start = position(player.position());
        GridPos target = position(destination);
        var profile = new NavProfile(new BodySize(player.getBbWidth(), player.getBbHeight()),
                new MovementLimits(1, 1, SAFE_DROP, 0), Set.of());
        var request = new NavRequest(start, NavGoals.within(target, ARRIVAL_RADIUS), profile,
                List.of(DangerLayer.INSTANCE), new SearchBudget(SEARCH_BUDGET, HORIZONTAL_RANGE));
        var result = planner.plan(new WorldGrid(player, start), request);
        var planned = result.plan();
        if (planned.isEmpty()) {
            return null;
        }
        var edges = planned.get().edges();
        if (edges.isEmpty()) {
            return null;
        }
        var edge = edges.getFirst();
        var feet = edge.endpoints().to();
        Vec3 waypoint = new Vec3(feet.x(), feet.y(), feet.z());
        return new Step(target, waypoint, edge.kind(), tick + ROUTE_REFRESH_TICKS);
    }

    private static void steer(ServerPlayer player, Step step, ControlIntent.Builder intent) {
        // 走向相对当前攻击视角换算，绕障碍时不会把正在施放的式转向路径拐点。
        Vec3 offset = step.waypoint().subtract(player.position());
        double distance = offset.horizontalDistance();
        if (distance <= WAYPOINT_RADIUS) {
            return;
        }
        double yaw = Math.toRadians(intent.getYaw());
        double sine = Math.sin(yaw);
        double cosine = Math.cos(yaw);
        intent.setForward((float) ((offset.z * cosine - offset.x * sine) / distance));
        intent.setSideways((float) ((offset.x * cosine + offset.z * sine) / distance));
        intent.setJump(player.onGround() && step.kind().equals(MoveKind.JUMP_UP));
    }

    static void clear(ServerPlayer player) {
        // 新会话不得沿用旧目标的半段跳跃或导航。
        steps.remove(player);
    }

    private static double horizontalDistance(Vec3 from, Vec3 to) {
        // 到达判定只看平面距离，实际台阶高度交给原版跳跃与碰撞处理。
        return to.subtract(from).horizontalDistance();
    }

    private static GridPos position(Vec3 position) {
        // 地面节点使用脚底上取整，半砖和土径由核心碰撞几何计算真实高度。
        return new GridPos((int) Math.floor(position.x), (int) Math.ceil(position.y), (int) Math.floor(position.z));
    }

    private record WorldGrid(ServerPlayer player, GridPos origin) implements VoxelGrid {
        @Override
        public GridBounds bounds() {
            // 有界读取防止一帧决策遍历整片世界。
            int x = origin.x();
            int y = origin.y();
            int z = origin.z();
            var minimum = new GridPos(x - HORIZONTAL_RANGE, y - VERTICAL_RANGE, z - HORIZONTAL_RANGE);
            var maximum = new GridPos(x + HORIZONTAL_RANGE, y + VERTICAL_RANGE, z + HORIZONTAL_RANGE);
            return new GridBounds(minimum, maximum);
        }

        @Override
        public Cell cellAt(GridPos position) {
            // 不加载未生成区块；流体与火类危险禁止进入，碰撞箱来自当前原版方块形状。
            var level = player.level();
            var pos = new BlockPos(position.x(), position.y(), position.z());
            if (!level.hasChunkAt(pos)) {
                return Cell.SOLID;
            }
            var state = level.getBlockState(pos);
            var fluid = state.getFluidState();
            if (!fluid.isEmpty()) {
                return new Cell(Terrain.WATER, Hazard.OTHER);
            }
            Hazard hazard = Hazard.NONE;
            if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.LAVA)
                    || state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.POWDER_SNOW)) {
                hazard = Hazard.OTHER;
            }
            var boxes = state.getCollisionShape(level, pos).toAabbs().stream()
                    .map(box -> new CollisionBox(new MotionPoint(box.minX, box.minY, box.minZ),
                            new MotionPoint(box.maxX, box.maxY, box.maxZ))).toList();
            Terrain terrain = Terrain.AIR;
            if (!boxes.isEmpty()) {
                terrain = Terrain.SOLID;
            }
            return new Cell(terrain, hazard, boxes);
        }
    }

    private record Step(GridPos destination, Vec3 waypoint, MoveKind kind, long refreshTick) { }
}
