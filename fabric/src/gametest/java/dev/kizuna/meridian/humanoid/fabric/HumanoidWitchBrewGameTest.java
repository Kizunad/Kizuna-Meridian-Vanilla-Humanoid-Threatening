package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.brewing.fabric.BrewItemStacks;
import dev.kizuna.meridian.brewing.server.BrewBodyProgress;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.human.server.HumanOpening;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpening;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpeningStage;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.item.ItemStack;

/** 生物宿主手动学卷和施放的协议回归，不注册女巫选式或出生学卷。 */
public final class HumanoidWitchBrewGameTest {
    private static final int TIMEOUT = 120;
    private static final int EFFECT_TICKS = 66;
    private static final double QI = 100;
    private static final MeridianOpening OPENED = MeridianOpening.newBuilder()
            .setStage(MeridianOpeningStage.OPENED)
            .setProgress(1)
            .build();

    @GameTest(maxTicks = TIMEOUT, structure = HumanoidWayfarerTestSupport.STRUCTURE)
    public void formalMobImbibeKeepsHeldBottle(GameTestHelper context) {
        // 生物使用与玩家相同的目录、主手 inspect 和炼药处理器，仅库存消费规则不同。
        Pillager mob = HumanoidWayfarerTestSupport.pillager(context, HumanoidTier.TRUE_QI);
        prepare(context, mob);
        TechniqueFabric.service().learn(mob.getUUID(), "meridian_witch:guard");
        ItemStack potion = BrewItemStacks.create(BrewKind.QI_GATHER, BrewQuality.COMMON, BrewUse.DRINK);
        mob.setItemInHand(InteractionHand.MAIN_HAND, potion);
        var reference = TechniqueReference.newBuilder()
                .setId("meridian_witch:imbibe")
                .setVersion(1)
                .build();
        var request = CastRequest.newBuilder()
                .setBodyId(HumanoidTechniqueHost.prepare(mob).body().snapshot().getId())
                .setCastId(UUID.randomUUID().toString())
                .setTechnique(reference)
                .build();
        var result = TechniqueFabric.service().cast(mob.getUUID(), request);
        context.assertTrue(result.hasBinding(), "生物服药起手失败：" + result);
        context.runAfterDelay(EFFECT_TICKS, () -> {
            var body = HumanoidTechniqueHost.prepare(mob).body().snapshot();
            context.assertTrue(BrewBodyProgress.read(body).getDosesCount() == 1, "生物服药未落身体药效");
            context.assertTrue(mob.getMainHandItem() == potion && potion.getCount() == 1, "生物施放消耗了药瓶");
            mob.discard();
            context.succeed();
        });
    }

    private static void prepare(GameTestHelper context, Pillager mob) {
        // 档位随机开脉不参与库存规则回归，通过真实恢复校验补全施放所需身体。
        var snapshot = HumanoidTechniqueHost.prepare(mob).body().snapshot().toBuilder();
        for (var state : snapshot.getMeridianStatesBuilderList()) {
            if (state.getOpening().getStage() != MeridianOpeningStage.OPENED) {
                var definition = snapshot.getStructureBuilder().getMeridiansBuilderList().stream()
                        .filter(value -> value.getId().equals(state.getId())).findFirst().orElseThrow();
                definition.setLumenVolume(definition.getLumenVolume() + HumanOpening.LUMEN_VOLUME_GAIN);
                state.setOpening(OPENED);
            }
        }
        HumanoidWayfarerTestSupport.restoreBody(mob, snapshot.build());
        var qi = DebugQi.newBuilder()
                .setMode(DebugQiMode.SET_INVENTORY)
                .setAmount(QI)
                .setDensity(1)
                .build();
        context.assertTrue(HumanoidHealthHost.debugQi(mob, qi) == FailureCode.FAILURE_CODE_UNSPECIFIED,
                "生物真元准备失败");
    }
}
