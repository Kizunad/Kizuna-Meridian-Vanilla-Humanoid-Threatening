package dev.kizuna.meridian.humanoid.fabric;

import com.mojang.authlib.GameProfile;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.technique.server.TechniqueState;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static dev.kizuna.meridian.humanoid.fabric.HumanoidWayfarerTestSupport.*;

/**
 * 盯上距离与连发站位：开阔地上远处的玩家照样被盯上，会连发的掠夺者远了走近、近了退开，
 * 停在射程边上出式；交战期间不被当作闲着。
 *
 * <p>两个用例各用一个空环境单独成批：世界里的玩家会被同批所有敌对生物看到。
 * GameTest 用屏障封住每个结构的四周、底和顶，屏障挡视线，所以用一块 112 格长的空地结构，
 * 玩家和掠夺者都站在里面。
 */
public final class HumanoidPillagerRangeGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_humanoid_test");
    private static final String FAR_ENVIRONMENT = "meridian_humanoid_test:pillager_range_far";
    private static final String NEAR_ENVIRONMENT = "meridian_humanoid_test:pillager_range_near";
    private static final String FIELD = "meridian_humanoid_test:open_field";
    /** 空地结构里地面的相对高度：GameTest 用屏障封底，站在结构最低一层。 */
    private static final int FLOOR_Y = 0;
    /** 包含重射承接、双臂恢复、继续退开和连续站位的观察时间。 */
    private static final int OBSERVATION_TICKS = 2400;
    private static final int TIMEOUT = OBSERVATION_TICKS + 20;
    /** 远处玩家离掠夺者的水平距离（格）：原版 32 格的跟踪距离外，盯上距离 100 格以内。 */
    private static final double FAR_DISTANCE = 98;
    /** 近处玩家离掠夺者的水平距离（格）：原版弩够得着，连发站位要退开。 */
    private static final double NEAR_DISTANCE = 40;
    /** 默认配置里掠夺者的盯上距离（格）。 */
    private static final double NOTICE_DISTANCE = 100;
    /** 连发站位带（格）：盯上距离再往里留 4 格，带宽 4 格。 */
    private static final double STANDOFF_MAXIMUM = 96;
    private static final double STANDOFF_MINIMUM = 92;
    /** 站位上沿的容差（格）：走近时到了上沿就停导航，惯性会多带一点。 */
    private static final double BAND_TOLERANCE = 1.0;
    /** 站位下沿的容差（格）：退开时按原版一格精度寻路到下沿上的点，会停在下沿外一两格内。 */
    private static final double PATH_ACCURACY = 2.0;
    /** 退开途中允许比起点近的最多格数：出手段站定时会被箭的后坐和碰撞推一点。 */
    private static final double APPROACH_TOLERANCE = 2.0;
    /** 连续这么多 tick 都在站位带里才算站定。 */
    private static final int SETTLED_TICKS = 40;
    private static final int NOT_OBSERVED_TICK = -1;
    /** 盯上时至少要在这么远（格）：任务要求 80 格外发现玩家。 */
    private static final double NOTICE_PROOF_DISTANCE = 80;
    /** 原版无动作计时过这个值，32 格外的生物开始有机会随机消失。 */
    private static final int VANILLA_IDLE_LIMIT = 600;
    private static final double EXACT = 1.0E-6;
    /** 对照物种离掠夺者的横向格数：在同一片空地上，互不挡路。 */
    private static final int CONTROL_OFFSET = 5;

    private enum Start { FAR, NEAR }

    @GameTest(maxTicks = TIMEOUT, structure = FIELD, environment = FAR_ENVIRONMENT)
    public void farPlayerIsNoticedAndPillagerHoldsAtVolleyReach(GameTestHelper context) {
        // 98 格外静止的玩家：掠夺者盯上它，走近到站位带，出式；卫道士没列在配置里，跟踪距离照旧。
        observe(context, Start.FAR);
    }

    @GameTest(maxTicks = TIMEOUT, structure = FIELD, environment = NEAR_ENVIRONMENT)
    public void nearPlayerMakesPillagerBackOffToVolleyReach(GameTestHelper context) {
        // 40 格处的玩家：掠夺者不往前凑，退到站位带。
        observe(context, Start.NEAR);
    }

    private static void observe(GameTestHelper context, Start start) {
        // 真炁掠夺者备好手经和真元后启用真实 AI；玩家只在开始时放一次，此后不控制任何一方。
        // 对照的卫道士站在掠夺者旁边，离玩家远过它自己的跟踪距离。
        Pillager mob = pillager(context, HumanoidTier.TRUE_QI);
        prepareQi(context, mob);
        FakePlayer player = player(context, mob, start);
        Mob control = context.spawn(EntityTypes.VINDICATOR, ORIGIN + CONTROL_OFFSET, ORIGIN, ORIGIN);
        control.snapTo(mob.getX() + CONTROL_OFFSET, mob.getY(), mob.getZ(), 0, 0);
        mob.setNoGravity(false /* noGravity */);
        mob.setNoAi(false /* aiDisabled */);
        new Watch(context, mob, control, player, start).tick(OBSERVATION_TICKS);
    }

    private static FakePlayer player(GameTestHelper context, Pillager mob, Start start) {
        // 远处的玩家站在掠夺者 +z 方向；近处的反过来，掠夺者先挪到玩家 +z 方向，退路沿空地往里。
        // 玩家站在结构地面上；生存模式才是敌人（测试世界默认创造）。
        ServerLevel level = context.getLevel();
        String name = "PillagerRange" + start;
        UUID id = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        var player = FakePlayer.get(level, new GameProfile(id, name));
        player.setGameMode(GameType.SURVIVAL);
        double distance = start == Start.FAR ? FAR_DISTANCE : 0;
        BlockPos floor = context.absolutePos(new BlockPos(ORIGIN, FLOOR_Y, ORIGIN));
        player.snapTo(mob.getX(), floor.getY(), mob.getZ() + distance, 0, 0);
        level.addNewPlayer(player);
        if (start == Start.NEAR) {
            mob.snapTo(mob.getX(), mob.getY(), mob.getZ() + NEAR_DISTANCE, 0, 0);
        }
        return player;
    }

    private static final class Watch {
        private final GameTestHelper context;
        private final Pillager mob;
        private final Mob control;
        private final FakePlayer player;
        private final Start start;
        private final Set<String> casts = new LinkedHashSet<>();
        private double noticedAt = -1;
        private double closest = Double.MAX_VALUE;
        private int noticedTick = NOT_OBSERVED_TICK;
        private int firstInBandTick = NOT_OBSERVED_TICK;
        private int idlePeak;
        private int settled;
        private int settledPeak;

        private Watch(GameTestHelper context, Pillager mob, Mob control, FakePlayer player, Start start) {
            // 观察身份固定，别的测试实体不参与断言。
            this.context = context;
            this.mob = mob;
            this.control = control;
            this.player = player;
            this.start = start;
        }

        private void tick(int remaining) {
            // 每 tick 只读状态：盯上时的距离、最近距离、交战后的无动作计时峰值、出过的式和连续在带里的 tick。
            double distance = mob.distanceTo(player);
            recordPosition(remaining, distance);
            recordCast();
            if (settled >= SETTLED_TICKS && (start == Start.NEAR || !casts.isEmpty())) {
                verify(distance);
                return;
            }
            if (remaining == 0) {
                String failure = "掠夺者没有在站位带站定 " + start + diagnosis(distance);
                clean();
                context.fail(failure);
                return;
            }
            context.runAfterDelay(1, () -> tick(remaining - 1));
        }

        private void recordPosition(int remaining, double distance) {
            // 时刻相对本用例开始计数；尚未认出目标或进带时保持 -1。
            int elapsed = OBSERVATION_TICKS - remaining;
            boolean noticed = mob.getTarget() == player;
            if (noticed && noticedTick < 0) {
                noticedAt = distance;
                noticedTick = elapsed;
            }
            if (noticed) {
                closest = Math.min(closest, distance);
                idlePeak = Math.max(idlePeak, mob.getNoActionTime());
            }
            boolean inBand = distance >= STANDOFF_MINIMUM - PATH_ACCURACY
                    && distance <= STANDOFF_MAXIMUM + BAND_TOLERANCE;
            if (inBand && firstInBandTick < 0) {
                firstInBandTick = elapsed;
            }
            if (noticed && inBand) {
                settled++;
            } else {
                settled = 0;
            }
            settledPeak = Math.max(settledPeak, settled);
        }

        private void recordCast() {
            // 只读已发布快照，记下出过的式，不替宿主推进施放。
            var snapshot = ((HumanoidEntityAccess) mob).meridian$humanoidBody().snapshot();
            var progress = TechniqueState.read(BodyProtobuf.fromMessage(snapshot));
            if (progress.hasActiveCast()) {
                casts.add(progress.getActiveCast().getRequest().getTechnique().getId());
            }
        }

        private void verify(double distance) {
            // 站定后核对：跟踪距离正好是配置值、对照物种由大脑接管但没改、远处起步的在 80 格外盯上、
            // 没往前凑、交战期间无动作计时没到原版随机消失的门槛。
            String evidence = diagnosis(distance);
            double notice = mob.getAttributeValue(Attributes.FOLLOW_RANGE);
            boolean controlManaged = HumanoidBrain.managed(control);
            var controlRange = control.getAttribute(Attributes.FOLLOW_RANGE);
            boolean controlWidened = controlRange.getModifier(HumanoidNotice.MODIFIER) != null;
            double startDistance = start == Start.FAR ? FAR_DISTANCE : NEAR_DISTANCE;
            clean();
            context.assertTrue(Math.abs(notice - NOTICE_DISTANCE) < EXACT, "掠夺者跟踪距离不是配置值" + evidence);
            context.assertTrue(controlManaged, "对照卫道士不在大脑接管下" + evidence);
            context.assertFalse(controlWidened, "没列在配置里的卫道士也被抬高了跟踪距离" + evidence);
            boolean farEnough = start == Start.NEAR || noticedAt > NOTICE_PROOF_DISTANCE;
            context.assertTrue(farEnough, "远处玩家没在 80 格外被盯上" + evidence);
            context.assertTrue(closest >= Math.min(startDistance, STANDOFF_MINIMUM) - APPROACH_TOLERANCE,
                    "掠夺者往前凑了" + evidence);
            context.assertTrue(idlePeak < VANILLA_IDLE_LIMIT, "交战期间无动作计时到了随机消失的门槛" + evidence);
            LOGGER.info("掠夺者站位通过 {}{}", start, evidence);
            context.succeed();
        }

        private String diagnosis(double distance) {
            // 失败与通过日志保留距离、首次盯上和进带时刻、连续计时峰值、出过的式和最近的决策。
            return " distance=" + distance + " noticedAt=" + noticedAt + " closest=" + closest
                    + " idlePeak=" + idlePeak + " noticedTick=" + noticedTick
                    + " firstInBandTick=" + firstInBandTick + " settledPeak=" + settledPeak + " casts=" + casts
                    + " target=" + mob.getTarget() + " directing=" + HumanoidBrain.directing(mob)
                    + " decisions=" + HumanoidBrain.decisions(mob.getUUID());
        }

        private void clean() {
            // 启用 AI 的实体和玩家不留到下一个批次。
            mob.discard();
            control.discard();
            context.getLevel().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        }
    }
}
