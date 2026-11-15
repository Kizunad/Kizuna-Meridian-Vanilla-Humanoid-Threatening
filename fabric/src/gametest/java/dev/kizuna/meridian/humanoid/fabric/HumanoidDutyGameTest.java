package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpeningStage;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.Vec3;

/** 职守的公开入口：任职时两臂训练成长到位、只升不降，凡人不动；「不退」的标签随实体存读。 */
public final class HumanoidDutyGameTest {
    private static final int TIMEOUT = 100;
    private static final BlockPos POS = new BlockPos(2, 2, 2);
    private static final double WANDERING_GROWTH = 0.2;
    private static final double TRUE_QI_GROWTH = 0.35;
    private static final double LOWER_GROWTH = 0.05;
    private static final double EPSILON = 1e-9;
    private static final String HAND_MERIDIAN = "human:hand_meridian";
    private static final String FOOT_MERIDIAN = "human:foot_meridian";
    private static final int BIRTH_ATTEMPTS = 60;

    @GameTest(maxTicks = TIMEOUT)
    public void trainedArmsReachTheGivenGrowthAndOnlyGoUp(GameTestHelper context) {
        // 游炁、真炁各按自己的数抬两臂肌肉的训练成长（不超过各块肌肉的上限），腿不动；再给更低的数不会降；凡人不动。
        for (HumanoidTier tier : HumanoidTier.values()) {
            Villager villager = villager(context, tier);
            List<BodyMessages.TissueState> before = body(villager).getTissueStatesList();
            context.assertTrue(HumanoidDuties.trainArms(villager, WANDERING_GROWTH, TRUE_QI_GROWTH), "任职没成：" + tier);
            double expected = switch (tier) {
                case MORTAL -> 0;
                case WANDERING_QI -> WANDERING_GROWTH;
                case TRUE_QI -> TRUE_QI_GROWTH;
            };
            verify(context, villager, before, expected, tier.id());
            HumanoidDuties.trainArms(villager, LOWER_GROWTH, LOWER_GROWTH);
            verify(context, villager, before, expected, tier.id() + "（再给更低的数）");
            villager.discard();
        }
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void standGroundAndTrainedArmsSurviveSaveAndLoad(GameTestHelper context) {
        // 「不退」记成记分板标签、两臂成长记在身体里：实体经原版存读档入口往返一轮，两样都还在；撤掉以后就没有了。
        Villager villager = villager(context, HumanoidTier.WANDERING_QI);
        List<BodyMessages.TissueState> before = body(villager).getTissueStatesList();
        context.assertTrue(!HumanoidDuties.standsGround(villager), "没挂过就带着不退");
        HumanoidDuties.standGround(villager, true);
        HumanoidDuties.trainArms(villager, WANDERING_GROWTH, TRUE_QI_GROWTH);
        var saved = HumanoidEntityNbt.save(villager);
        villager.discard();
        Villager reloaded = EntityTypes.VILLAGER.create(context.getLevel(), EntitySpawnReason.LOAD);
        HumanoidEntityNbt.load(reloaded, saved);
        reloaded.setNoAi(true /* aiDisabled */);
        context.assertTrue(context.getLevel().addFreshEntity(reloaded), "读回来的村民未加入世界");
        context.assertTrue(HumanoidDuties.standsGround(reloaded), "存读一轮以后不退的标签没了");
        verify(context, reloaded, before, WANDERING_GROWTH, "存读一轮以后");
        HumanoidDuties.standGround(reloaded, false);
        context.assertTrue(!HumanoidDuties.standsGround(reloaded), "撤掉以后还在");
        reloaded.discard();
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void ensureOpenedOpensOneHandMeridianOnlyWhenNoneIsOpen(GameTestHelper context) {
        // 一条手经都没开的游炁村民：保底开一条（按构型里的先后是第一条），内腔体积照出生时开脉的扩容量加，
        // 别的经脉不动；再调一次不再开。本来就开着手经的村民不动。凡人不动。
        Villager closed = villager(context, HumanoidTier.WANDERING_QI, false /* handOpened */);
        Villager opened = villager(context, HumanoidTier.WANDERING_QI, true /* handOpened */);
        Villager mortal = villager(context, HumanoidTier.MORTAL);
        BodyMessages.BodySnapshot before = body(closed);
        BodyMessages.BodySnapshot openedBefore = body(opened);
        BodyMessages.BodySnapshot mortalBefore = body(mortal);
        for (Villager villager : List.of(closed, opened, mortal)) {
            context.assertTrue(HumanoidDuties.ensureOpened(villager, HAND_MERIDIAN), "保底开脉没成");
            context.assertTrue(HumanoidDuties.ensureOpened(villager, HAND_MERIDIAN), "再调一次没成");
        }
        BodyMessages.BodySnapshot after = body(closed);
        int first = firstHand(after);
        double gain = HumanoidFabric.config().openedLumenGain();
        for (int index = 0; index < after.getMeridianStatesCount(); index++) {
            boolean target = index == first;
            context.assertTrue(target == (opening(after, index) == MeridianOpeningStage.OPENED
                    && opening(before, index) != MeridianOpeningStage.OPENED), "开的不是第一条手经，或多开了：第 " + index + " 条");
            double grown = after.getStructure().getMeridians(index).getLumenVolume()
                    - before.getStructure().getMeridians(index).getLumenVolume();
            context.assertTrue(Math.abs(grown - (target ? gain : 0)) < EPSILON, "第 " + index + " 条的内腔体积变了 " + grown);
        }
        context.assertTrue(opened(body(opened)).equals(opened(openedBefore)), "本来开着手经的村民被多开了");
        context.assertTrue(opened(body(mortal)).equals(opened(mortalBefore)), "凡人被开了脉");
        List.of(closed, opened, mortal).forEach(Villager::discard);
        context.succeed();
    }

    private static MeridianOpeningStage opening(BodyMessages.BodySnapshot body, int index) {
        return body.getMeridianStates(index).getOpening().getStage();
    }

    private static List<Integer> opened(BodyMessages.BodySnapshot body) {
        // 哪几条经脉是开着的。
        return java.util.stream.IntStream.range(0, body.getMeridianStatesCount())
                .filter(index -> opening(body, index) == MeridianOpeningStage.OPENED).boxed().toList();
    }

    private static int firstHand(BodyMessages.BodySnapshot body) {
        // 构型里第一条手经的下标。
        for (int index = 0; index < body.getMeridianStatesCount(); index++) {
            if (body.getStructure().getMeridians(index).getTagsList().contains(HAND_MERIDIAN)) {
                return index;
            }
        }
        throw new IllegalStateException("身体里没有手经");
    }

    private static boolean handOpened(BodyMessages.BodySnapshot body) {
        return opened(body).stream()
                .anyMatch(index -> body.getStructure().getMeridians(index).getTagsList().contains(HAND_MERIDIAN));
    }

    private static Villager villager(GameTestHelper context, HumanoidTier tier, boolean handOpened) {
        // 出生的身体是抽的，抽到什么和所在区域有关，不能指望一定抽得到「一条手经都没开」的。
        // 要没开手经的：拿凡人的身体先开一条足经，它就是一个游炁、手经一条没开的村民。要开着手经的：抽，不合的丢掉重出。
        if (!handOpened) {
            Villager villager = villager(context, HumanoidTier.MORTAL);
            HumanoidTechniqueHost.prepare(villager).body().openOne(FOOT_MERIDIAN,
                    HumanoidFabric.config().openedLumenGain());
            return villager;
        }
        for (int attempt = 0; attempt < BIRTH_ATTEMPTS; attempt++) {
            Villager villager = villager(context, tier);
            if (handOpened(body(villager))) {
                return villager;
            }
            villager.discard();
        }
        throw new IllegalStateException("连出 " + BIRTH_ATTEMPTS + " 个村民都没有开着的手经");
    }

    private static void verify(GameTestHelper context, Villager villager, List<BodyMessages.TissueState> before,
                               double growth, String where) {
        // 两臂肌肉：声明了训练的每一块都是「给定值和上限里小的那个」，且不低于原值；别的组织一律没变。
        BodyMessages.BodySnapshot body = body(villager);
        int arms = 0;
        for (int index = 0; index < body.getTissueStatesCount(); index++) {
            BodyMessages.TissueDefinition definition = body.getStructure().getTissues(index);
            BodyMessages.TissueState state = body.getTissueStates(index);
            boolean arm = definition.getTagsList().contains(HumanoidFighters.ARM_MUSCLE) && definition.hasTraining();
            double was = before.get(index).getTrainingGrowth();
            double expected = arm ? Math.max(was, Math.min(growth, definition.getTraining().getMaximumGrowth())) : was;
            context.assertTrue(Math.abs(state.getTrainingGrowth() - expected) < EPSILON, where + " 的 "
                    + definition.getId() + " 训练成长是 " + state.getTrainingGrowth() + "，应当是 " + expected);
            arms += arm ? 1 : 0;
        }
        context.assertTrue(arms > 0, "身体里没有带训练定义的两臂肌肉，这条用例什么都没验");
    }

    private static BodyMessages.BodySnapshot body(Villager villager) {
        return HumanoidTechniqueHost.prepare(villager).body().snapshot();
    }

    private static Villager villager(GameTestHelper context, HumanoidTier tier) {
        // 指定档位出生、不开 AI，加入世界以后才能走正式的准备流程。
        Villager villager = EntityTypes.VILLAGER.create(context.getLevel(), EntitySpawnReason.COMMAND);
        Vec3 position = Vec3.atBottomCenterOf(context.absolutePos(POS));
        villager.setUUID(UUID.randomUUID());
        villager.snapTo(position.x, position.y, position.z, 0, 0);
        villager.setNoAi(true /* aiDisabled */);
        HumanoidSpeciesBirths.birth(villager, tier);
        context.assertTrue(context.getLevel().addFreshEntity(villager), "村民未加入世界");
        return villager;
    }
}
