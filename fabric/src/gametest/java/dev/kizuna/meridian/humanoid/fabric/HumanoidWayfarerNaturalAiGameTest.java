package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.human.server.HumanOpening;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpening;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpeningStage;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.server.TechniqueBinder;
import dev.kizuna.meridian.technique.server.TechniqueAvailability;
import dev.kizuna.meridian.technique.server.TechniqueState;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static dev.kizuna.meridian.humanoid.fabric.HumanoidWayfarerTestSupport.*;

/** 原版实体 AI tick 自主决策、起手和生成箭，不直接请求施放或发射效果。 */
public final class HumanoidWayfarerNaturalAiGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_humanoid_test");
    private static final String LEGACY_ENVIRONMENT = "meridian_humanoid_test:pillager_basic_volumes";
    private static final int OBSERVATION_TICKS = 600;
    private static final int TIMEOUT = OBSERVATION_TICKS + 20;
    private static final double TARGET_DISTANCE = 12;
    private static final double VANILLA_DISTANCE = 6;
    private static final double SEARCH_RADIUS = 40;
    private static final Set<String> REQUIRED_HAND_MERIDIANS = Set.of("left_lung_meridian", "right_lung_meridian");
    private static final MeridianOpening OPENED = MeridianOpening.newBuilder()
            .setStage(MeridianOpeningStage.OPENED)
            .setProgress(1)
            .build();

    private enum Shooting { WAYFARER, VANILLA, VANILLA_AFTER_WAYFARER }

    @GameTest(maxTicks = TIMEOUT, structure = STRUCTURE)
    public void wanderingPillagerNaturallyCastsAndShoots(GameTestHelper context) {
        // 游炁默认只学弩卷，必须由自然决策产生真实行矢箭。
        observe(context, HumanoidTier.WANDERING_QI, Shooting.WAYFARER);
    }

    @GameTest(maxTicks = TIMEOUT, structure = STRUCTURE)
    public void trueQiPillagerNaturallyCastsAndShoots(GameTestHelper context) {
        // 真炁默认可换备用弓；依然要求真实 AI 完整走到行矢发箭。
        observe(context, HumanoidTier.TRUE_QI, Shooting.WAYFARER);
    }

    @GameTest(maxTicks = TIMEOUT, structure = STRUCTURE, environment = LEGACY_ENVIRONMENT)
    public void basicVolumesLeaveVanillaCrossbowTimeToShoot(GameTestHelper context) {
        // 只有基础三卷的旧配置也要在准备后交还原版 Goal，允许完整上弦和射击。
        observe(context, HumanoidTier.TRUE_QI, Shooting.VANILLA);
    }

    @GameTest(maxTicks = TIMEOUT, structure = STRUCTURE)
    public void completedWayfarerCastReleasesVanillaCrossbowGoal(GameTestHelper context) {
        // 自然连弩完整结束后，将目标移入没有弩式候选的距离，原版 Goal 必须恢复上弦与发箭。
        observe(context, HumanoidTier.WANDERING_QI, Shooting.VANILLA_AFTER_WAYFARER);
    }

    private static void observe(GameTestHelper context, HumanoidTier tier, Shooting expected) {
        // 铺地后启用真实 AI；目标与射手只在开始时放置一次，后续不控制它们的移动或攻击。
        floor(context);
        Pillager mob = pillager(context, tier);
        if (expected == Shooting.VANILLA) {
            restoreUntrainedArms(mob);
        } else {
            prepareQi(context, mob);
        }
        Cow target = target(context, mob, TARGET_DISTANCE);
        mob.setNoGravity(false /* noGravity */);
        mob.setNoAi(false /* aiDisabled */);
        var watch = new Watch(context, mob, target, expected);
        watch.tick(OBSERVATION_TICKS);
    }

    private static void prepareQi(GameTestHelper context, Pillager mob) {
        // 随机游炁身体可能只开一条手经；射击回归显式提供连弩需要的两条手经，再填充真元。
        var snapshot = HumanoidTechniqueHost.prepare(mob).body().snapshot().toBuilder();
        for (String meridian : REQUIRED_HAND_MERIDIANS) {
            openMeridian(snapshot, meridian);
        }
        restoreBody(mob, snapshot.build());
        DebugQi request = DebugQi.newBuilder()
                .setMode(DebugQiMode.FILL_LUMENS)
                .build();
        FailureCode result = HumanoidHealthHost.debugQi(mob, request);
        context.assertTrue(result == FailureCode.FAILURE_CODE_UNSPECIFIED, "射击夹具真元准备失败：" + result);
        var body = body(mob);
        var definition = definition(mob, "repeater");
        var held = HumanoidWayfarerWeapons.inspect(mob);
        var binding = TechniqueBinder.bind(body, definition, Hand.MAIN_HAND, held.getMainHandSide());
        context.assertTrue(binding.hasBinding(), "射击夹具必须能绑定连弩：" + binding);
        context.assertTrue(TechniqueAvailability.affordable(body, definition, binding.getBinding()),
                "射击夹具必须能支付连弩费用");
    }

    private static void openMeridian(BodyMessages.BodySnapshot.Builder snapshot, String id) {
        // 与人体开脉内容采用相同的容量增量，已开经脉不重复扩容；通过正式恢复入口校验。
        var state = snapshot.getMeridianStatesBuilderList().stream()
                .filter(meridian -> meridian.getId().equals(id))
                .findFirst().orElseThrow();
        if (state.getOpening().getStage() == MeridianOpeningStage.OPENED) {
            return;
        }
        var definition = snapshot.getStructureBuilder().getMeridiansBuilderList().stream()
                .filter(meridian -> meridian.getId().equals(id))
                .findFirst().orElseThrow();
        definition.setLumenVolume(definition.getLumenVolume() + HumanOpening.LUMEN_VOLUME_GAIN);
        state.setOpening(OPENED);
    }

    private static void restoreUntrainedArms(Pillager mob) {
        // 旧 Windows 配置没有臂肌成长，按已有身体恢复，避免新版出生默认值掩盖旧配置行为。
        var snapshot = HumanoidTechniqueHost.prepare(mob).body().snapshot();
        var untrained = snapshot.toBuilder();
        for (int index = 0; index < snapshot.getStructure().getTissuesCount(); index++) {
            var tissue = snapshot.getStructure().getTissues(index);
            if (tissue.getTagsList().contains("human:arm_muscle")) {
                untrained.getTissueStatesBuilder(index).setTrainingGrowth(0);
            }
        }
        restoreBody(mob, untrained.build());
    }

    private static final class Watch {
        private final GameTestHelper context;
        private final Pillager mob;
        private final Cow target;
        private final Shooting expected;
        private final Set<String> casts = new LinkedHashSet<>();
        private final Set<UUID> arrowsSeen = new LinkedHashSet<>();
        private boolean usedCrossbow;
        private boolean wayfarerShot;
        private boolean targetMoved;
        private boolean resumedCrossbow;

        private Watch(GameTestHelper context, Pillager mob, Cow target, Shooting expected) {
            // 观察身份固定，其他测试实体和箭不参与断言。
            this.context = context;
            this.mob = mob;
            this.target = target;
            this.expected = expected;
        }

        private void tick(int remaining) {
            // 只读已发布快照，避免观察器代替真实宿主推进施放或学卷。
            var snapshot = ((HumanoidEntityAccess) mob).meridian$humanoidBody().snapshot();
            var progress = TechniqueState.read(BodyProtobuf.fromMessage(snapshot));
            if (progress.hasActiveCast()) {
                String id = progress.getActiveCast().getRequest().getTechnique().getId();
                casts.add(id);
            }
            usedCrossbow |= mob.isUsingItem();
            Shooting observed = Shooting.VANILLA;
            if (HumanoidTechniqueHost.castingWayfarer(mob)) {
                observed = Shooting.WAYFARER;
            }
            prepareReturnToVanilla(progress);
            var freshArrows = mob.level().getEntitiesOfClass(AbstractArrow.class,
                    mob.getBoundingBox().inflate(SEARCH_RADIUS), arrow -> arrow.getOwner() == mob).stream()
                    .filter(arrow -> arrowsSeen.add(arrow.getUUID()))
                    .toList();
            wayfarerShot |= observed == Shooting.WAYFARER && !freshArrows.isEmpty();
            if (!freshArrows.isEmpty() && expectedShot(observed)) {
                finish("自然发箭通过 " + expected + " arrows=" + arrowsSeen.size() + diagnosis());
                return;
            }
            if (remaining == 0) {
                String failure = "自然发箭超时 " + expected + diagnosis();
                clean();
                context.fail(failure);
                return;
            }
            context.runAfterDelay(1, () -> tick(remaining - 1));
        }

        private void prepareReturnToVanilla(TechniqueProgress progress) {
            // 已完成整段行矢才移动目标一次；随后只观察原版上弦，不再改目标或射手状态。
            if (expected != Shooting.VANILLA_AFTER_WAYFARER || !wayfarerShot || progress.hasActiveCast()) {
                return;
            }
            if (!targetMoved) {
                target.setPos(mob.position().add(0, 0, VANILLA_DISTANCE));
                target.setDeltaMovement(Vec3.ZERO);
                targetMoved = true;
            }
            resumedCrossbow |= mob.isUsingItem();
        }

        private boolean expectedShot(Shooting observed) {
            // 只接受本 tick 首次看到的箭，旧的行矢箭不能充当恢复原版射击的证据。
            return switch (expected) {
                case WAYFARER -> observed == Shooting.WAYFARER;
                case VANILLA -> usedCrossbow && observed == Shooting.VANILLA && !HumanoidBrain.directing(mob);
                case VANILLA_AFTER_WAYFARER -> resumedCrossbow && observed == Shooting.VANILLA
                        && !HumanoidBrain.directing(mob);
            };
        }

        private String diagnosis() {
            // 失败信息保留实际决策、施放与原版使用状态，以便区分控制阻塞与发箭失败。
            var snapshot = ((HumanoidEntityAccess) mob).meridian$humanoidBody().snapshot();
            var body = BodyProtobuf.fromMessage(snapshot);
            return " casts=" + casts + " usedCrossbow=" + usedCrossbow
                    + " wayfarerShot=" + wayfarerShot + " resumedCrossbow=" + resumedCrossbow
                    + " distance=" + mob.distanceTo(target) + " directing=" + HumanoidBrain.directing(mob)
                    + " blocked=" + HumanoidBrain.blocksRanged(mob)
                    + " condition=" + HumanoidFighters.condition(body)
                    + " decisions=" + HumanoidBrain.decisions(mob.getUUID());
        }

        private void finish(String evidence) {
            // 日志记录实际自然发箭证据，再清理测试实体。
            LOGGER.info(evidence);
            clean();
            context.succeed();
        }

        private void clean() {
            // 启用 AI 的测试实体不留到下一个环境批次。
            mob.discard();
            target.discard();
        }
    }
}
