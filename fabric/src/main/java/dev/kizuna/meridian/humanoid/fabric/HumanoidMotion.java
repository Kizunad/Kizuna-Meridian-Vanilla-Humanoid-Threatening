package dev.kizuna.meridian.humanoid.fabric;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/** 把大脑指令落到原版导航与朝向上；每 tick 由接入适配器调用，路径按固定节奏重算，上次寻路的时刻记在 HumanoidMind。 */
final class HumanoidMotion {
    private static final float LOOK_YAW_SPEED = 30;
    private static final float LOOK_PITCH_SPEED = 30;
    private static final int PATH_REFRESH_TICKS = 10;
    /** 导航空闲（贴身、已到、无路）时两次寻路之间至少隔这么多 tick；原版近战目标是 4 到 10 tick。 */
    private static final int IDLE_REPATH_TICKS = 5;
    private static final int RETREAT_HORIZONTAL_RANGE = 16;
    private static final int RETREAT_VERTICAL_RANGE = 7;
    private static final int[] RETREAT_STEPS = {RETREAT_HORIZONTAL_RANGE, 8, 4, 2};
    private static final double RETREAT_GAIN = 1;
    private static final double ARRIVED_DISTANCE = 1;
    private static final double SUPPORT_PROBE_DEPTH = 0.1;
    /** 对手在这个距离以外、自己又在墙角时才沿墙换角；换去的角离对手也要在这个距离以外。 */
    private static final double CORNER_SWITCH_FOE_DISTANCE = 3;
    /** 东西、南北两个轴上都有一侧走不到这么多格就到头，算在墙角；走到头不超过这么多格的那一侧就是眼下这个角。 */
    private static final int CORNER_REACH = 2;

    /** 指令里的移动方式：交还原版、跟随对手、保持距离带、走到某点、原地不动、原地转身面向对手。 */
    enum Movement { VANILLA, FOLLOW, HOLD_RANGE, MOVE_TO, STAY, FACE }

    /** 导航快慢：接近与逃离，各接入方式给出自己的倍率。 */
    enum Pace { APPROACH, FLEE }

    /** 保持距离带的上下界。 */
    record Range(double minimum, double maximum) {
        Range {
            // 距离带必须有限且有序，避免导航在异常配置下反复重算。
            if (!Double.isFinite(minimum) || !Double.isFinite(maximum) || minimum < 0 || maximum < minimum) {
                throw new IllegalArgumentException("移动距离带非法");
            }
        }
    }

    /** 一条移动指令；只有走到某点或保持距离带时携带附加数据。 */
    record Directive(Movement movement, Optional<Vec3> destination, Pace pace, Optional<Range> range) {
        static final Directive RELEASED = new Directive(Movement.VANILLA, Optional.empty(), Pace.APPROACH,
                Optional.empty());
        static final Directive FOLLOWING = new Directive(Movement.FOLLOW, Optional.empty(), Pace.APPROACH,
                Optional.empty());
        static final Directive STAYING = new Directive(Movement.STAY, Optional.empty(), Pace.APPROACH,
                Optional.empty());
        static final Directive FACING = new Directive(Movement.FACE, Optional.empty(), Pace.APPROACH,
                Optional.empty());

        static Directive moveTo(Vec3 destination, Pace pace) {
            // 走到某点的指令。
            return new Directive(Movement.MOVE_TO, Optional.of(destination), pace, Optional.empty());
        }

        static Directive holdRange(double minimum, double maximum) {
            // 在跃步最小距离与打击距离之间保持可突击带。
            return new Directive(Movement.HOLD_RANGE, Optional.empty(), Pace.APPROACH,
                    Optional.of(new Range(minimum, maximum)));
        }
    }

    /** 一种接入方式的导航倍率。 */
    record Speeds(double approach, double flee) {
        double of(Pace pace) {
            // 按快慢取倍率。
            if (pace == Pace.FLEE) {
                return flee;
            }
            return approach;
        }
    }

    private HumanoidMotion() {
        // 纯执行，不保存指令。
    }

    static void apply(Mob mob, HumanoidMind mind, Directive directive, Speeds speeds) {
        // 有对手时始终看着它；跟随与走点按节奏重算路径，原地时停下导航。
        Optional<LivingEntity> opponent = mind.opponent();
        opponent.ifPresent(target -> mob.getLookControl().setLookAt(target, LOOK_YAW_SPEED, LOOK_PITCH_SPEED));
        boolean refresh = pathDue(mob, mind);
        switch (directive.movement()) {
            case FOLLOW -> {
                if (refresh && opponent.isPresent()) {
                    mob.getNavigation().moveTo(opponent.get(), speeds.of(directive.pace()));
                    mind.pathed(mob.tickCount);
                }
            }
            case HOLD_RANGE -> {
                if (opponent.isPresent()) {
                    Range range = directive.range().orElseThrow();
                    applyHoldRange(mob, mind, range, speeds);
                }
            }
            case MOVE_TO -> applyMoveTo(mob, mind, directive, speeds, refresh);
            case STAY -> mob.getNavigation().stop();
            case FACE -> {
                // 视线控制只转头；身前、身后和打击扇区按身体朝向算，站着不走时要自己把身体转过去。
                mob.getNavigation().stop();
                opponent.ifPresent(target -> mob.lookAt(target, LOOK_YAW_SPEED, LOOK_PITCH_SPEED));
            }
            case VANILLA -> {
                // 原版控制自己移动。
            }
        }
    }

    private static void applyMoveTo(Mob mob, HumanoidMind mind, Directive directive, Speeds speeds,
                                    boolean refresh) {
        // 走向落点，按节奏重算路径；手上那条路径若不是为当前导航目标算的，当场清掉重新寻路。
        Vec3 destination = directive.destination().orElseThrow();
        PathNavigation navigation = mob.getNavigation();
        boolean stale = probedOver(navigation);
        if (stale) {
            navigation.stop();
        }
        boolean away = mob.position().distanceToSqr(destination) > ARRIVED_DISTANCE * ARRIVED_DISTANCE;
        if ((refresh || stale) && away) {
            navigation.moveTo(destination.x, destination.y, destination.z, speeds.of(directive.pace()));
            mind.pathed(mob.tickCount);
        }
    }

    private static boolean probedOver(PathNavigation navigation) {
        // 试算退路会把原版导航记的目标格改成新落点，却不换掉正在走的路径；之后按同一格寻路，原版直接沿用旧路径。
        // 正在走的路径终点与导航目标格不一致，说明它通往别处（例如原版攻击走向对手的那条），不能冒充去新落点的路。
        Path path = navigation.getPath();
        return path != null && !path.isDone() && !path.getTarget().equals(navigation.getTargetPos());
    }

    private static boolean pathDue(Mob mob, HumanoidMind mind) {
        // 固定节拍照旧重算；导航空闲时也重算，但离上次寻路太近就等一等。
        // 贴身或无路时导航一直空闲，不加这个间隔就会每 tick 寻一次路。
        if (mob.tickCount % PATH_REFRESH_TICKS == 0) {
            return true;
        }
        return mob.getNavigation().isDone() && !mind.pathedWithin(mob.tickCount, IDLE_REPATH_TICKS);
    }

    private static void applyHoldRange(Mob mob, HumanoidMind mind, Range range, Speeds speeds) {
        // 太近时退到带外，太远时接近，处于带内停止导航保护后续跃步距离。
        LivingEntity opponent = mind.opponent().orElseThrow();
        double distance = distance(mob, opponent);
        if (distance < range.minimum()) {
            if (pathDue(mob, mind)) {
                Vec3 destination = opponent.position().add(away(mob, opponent).scale(range.minimum()));
                mob.getNavigation().moveTo(destination.x, destination.y, destination.z, speeds.flee());
                mind.pathed(mob.tickCount);
            }
            return;
        }
        if (distance <= range.maximum()) {
            mob.getNavigation().stop();
            return;
        }
        if (pathDue(mob, mind)) {
            mob.getNavigation().moveTo(opponent, speeds.approach());
            mind.pathed(mob.tickCount);
        }
    }

    static Optional<Vec3> retreatPoint(Mob mob, LivingEntity threat, Directive previous, double safeDistance) {
        // 退路要比现在离威胁更远，或至少到安全距离。
        double minimumDistance = Math.max(mob.distanceTo(threat) + RETREAT_GAIN, safeDistance);
        return pointAway(mob, threat, previous, minimumDistance);
    }

    static Optional<Vec3> sidestepPoint(Mob mob, LivingEntity threat, Directive previous) {
        // 拉不开时退而求其次：不比现在离威胁更近的可达点，贴墙横着让开。
        return pointAway(mob, threat, previous, mob.distanceTo(threat));
    }

    static Optional<Vec3> cornerSwitchPoint(Mob mob, LivingEntity threat, Directive previous) {
        // 横着也让不开时：对手还远、自己又在墙角，就沿墙去另一个角，不在原地等对手过来；
        // 沿用尚未走完的同一条路，对手逼近到 CORNER_SWITCH_FOE_DISTANCE 格以内就不再换。
        if (distance(mob, threat) <= CORNER_SWITCH_FOE_DISTANCE) {
            return Optional.empty();
        }
        if (usableRetreat(mob, threat, previous, CORNER_SWITCH_FOE_DISTANCE)) {
            return previous.destination();
        }
        Map<Direction, WallRun> runs = new EnumMap<>(Direction.class);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            runs.put(direction, wallRun(mob, direction));
        }
        if (!inCorner(runs)) {
            return Optional.empty();
        }
        Set<BlockPos> candidates = otherCorners(runs, threat);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        var path = mob.getNavigation().createPath(candidates, 0);
        if (path == null || !path.canReach()) {
            return Optional.empty();
        }
        return Optional.of(Vec3.atBottomCenterOf(path.getTarget()));
    }

    /** 从脚下沿一个水平方向一格一格走，到下一格站不住为止；end 是最后一格站得住的位置，length 是走了几格。 */
    private record WallRun(BlockPos end, int length) { }

    private static WallRun wallRun(Mob mob, Direction direction) {
        // 平地上沿墙走，最远到退路搜索的水平范围。
        BlockPos end = mob.blockPosition();
        int length = 0;
        while (length < RETREAT_HORIZONTAL_RANGE && standable(mob, end.relative(direction))) {
            end = end.relative(direction);
            length++;
        }
        return new WallRun(end, length);
    }

    private static boolean inCorner(Map<Direction, WallRun> runs) {
        // 东西向和南北向各有一侧很快就到墙。
        int eastWest = Math.min(runs.get(Direction.EAST).length(), runs.get(Direction.WEST).length());
        int northSouth = Math.min(runs.get(Direction.NORTH).length(), runs.get(Direction.SOUTH).length());
        return eastWest <= CORNER_REACH && northSouth <= CORNER_REACH;
    }

    private static Set<BlockPos> otherCorners(Map<Direction, WallRun> runs, LivingEntity threat) {
        // 沿墙走得远的那几头是别的角；走不远的那头还在眼下这个角里。离对手太近的角不去。
        double minimumSqr = CORNER_SWITCH_FOE_DISTANCE * CORNER_SWITCH_FOE_DISTANCE;
        Set<BlockPos> corners = new LinkedHashSet<>();
        for (WallRun run : runs.values()) {
            boolean far = run.length() > CORNER_REACH;
            boolean clear = Vec3.atBottomCenterOf(run.end()).distanceToSqr(threat.position()) > minimumSqr;
            if (far && clear) {
                corners.add(run.end());
            }
        }
        return corners;
    }

    private static Optional<Vec3> pointAway(Mob mob, LivingEntity threat, Directive previous,
                                            double minimumDistance) {
        // 沿用尚未走完的可达退路；原版抽样与附近候选统一交给寻路器，失败明确表示无路可退。
        if (usableRetreat(mob, threat, previous, minimumDistance)) {
            return previous.destination();
        }
        for (int step : RETREAT_STEPS) {
            Set<BlockPos> candidates = retreatCandidates(mob, threat, minimumDistance, step);
            if (candidates.isEmpty()) {
                continue;
            }
            var path = mob.getNavigation().createPath(candidates, 0);
            if (path != null && path.canReach()) {
                return Optional.of(Vec3.atBottomCenterOf(path.getTarget()));
            }
        }
        return Optional.empty();
    }

    private static boolean usableRetreat(Mob mob, LivingEntity threat, Directive directive, double minimumDistance) {
        // 导航已经失败、到达或目标已不再拉开距离时必须重算，不能永久保留墙外坐标。
        var navigation = mob.getNavigation();
        var path = navigation.getPath();
        if (directive.movement() != Movement.MOVE_TO || directive.pace() != Pace.FLEE
                || navigation.isDone() || path == null || !path.canReach()) {
            return false;
        }
        Vec3 destination = directive.destination().orElseThrow();
        return mob.position().distanceTo(destination) > ARRIVED_DISTANCE
                && destination.distanceToSqr(threat.position()) >= minimumDistance * minimumDistance;
    }

    private static Set<BlockPos> retreatCandidates(Mob mob, LivingEntity threat, double minimumDistance, int step) {
        // 优先寻找长退路，再逐圈缩小；同一圈只做一次多目标寻路，墙边也能沿侧面退让。
        Set<BlockPos> candidates = new LinkedHashSet<>();
        if (step == RETREAT_HORIZONTAL_RANGE && mob instanceof PathfinderMob walker) {
            Vec3 found = DefaultRandomPos.getPosAway(walker, RETREAT_HORIZONTAL_RANGE, RETREAT_VERTICAL_RANGE,
                    threat.position());
            if (found != null) {
                addRetreatCandidate(mob, threat, BlockPos.containing(found), minimumDistance, candidates);
            }
        }
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x != 0 || z != 0) {
                    BlockPos point = mob.blockPosition().offset(x * step, 0, z * step);
                    addRetreatCandidate(mob, threat, point, minimumDistance, candidates);
                }
            }
        }
        return candidates;
    }

    private static void addRetreatCandidate(Mob mob, LivingEntity threat, BlockPos candidate, double minimumDistance,
                                             Set<BlockPos> candidates) {
        // 目的地须能站立、容纳实体且确实增加间距；高度搜索处理台阶和起跳后的坐标。
        BlockPos.findClosestMatch(candidate, 0, RETREAT_VERTICAL_RANGE, point -> {
            Vec3 destination = Vec3.atBottomCenterOf(point);
            return destination.distanceToSqr(threat.position()) >= minimumDistance * minimumDistance
                    && standable(mob, point);
        }).ifPresent(point -> candidates.add(point.immutable()));
    }

    private static boolean standable(Mob mob, BlockPos point) {
        // 那一格容得下实体，脚下有支撑。
        Vec3 destination = Vec3.atBottomCenterOf(point);
        var bounds = mob.getBoundingBox().move(destination.subtract(mob.position()));
        return mob.level().noCollision(mob, bounds)
                && !mob.level().noCollision(mob, bounds.move(0, -SUPPORT_PROBE_DEPTH, 0));
    }

    static Vec3 away(Entity self, Entity threat) {
        // 从威胁指向自己的水平单位向量；重合时取自己朝向的反方向。
        Vec3 delta = self.position().subtract(threat.position()).multiply(1, 0, 1);
        if (delta.lengthSqr() == 0) {
            return self.getLookAngle().multiply(-1, 0, -1).normalize();
        }
        return delta.normalize();
    }

    static double distance(Entity self, Entity other) {
        // 水平距离。
        double dx = other.getX() - self.getX();
        double dz = other.getZ() - self.getZ();
        return Math.hypot(dx, dz);
    }
}
