package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.brewing.fabric.BrewItemStacks;
import dev.kizuna.meridian.brewing.fabric.BrewQueries;
import dev.kizuna.meridian.brewing.fabric.BrewResidues;
import dev.kizuna.meridian.brewing.fabric.BrewingRuntime;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.phys.Vec3;

/** 袭击治疗与魅惑免疫都由实际身体、阵营和药效状态验证。 */
public final class HumanoidWitchRaidGameTest {
    private static final double CLOSE = 1.5;
    private static final double ALLY_OFFSET = 0.5;
    private static final int CHARM_FINISHED = 125;
    private static final double LOW_BRAIN_REMAINING = 0.05;
    private static final int NATURAL_TIMEOUT = 100;

    @GameTest(maxTicks = WitchTestSupport.TIMEOUT, structure = WitchTestSupport.STRUCTURE)
    public void raidHealsInjuredAllyLegsWithBitterYellow(GameTestHelper context) {
        // 同一活动袭击的腿伤同伴走正式掷引，下药目标不是敌人。
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.TRUE_QI);
        Pillager ally = ally(context, witch);
        WitchTestSupport.injury(ally, 1, WitchTestSupport.INJURED);
        WitchTestSupport.choose(context, witch, HumanoidWitchAi.LOB, BrewKind.BITTER_YELLOW);
        context.runAfterDelay(NATURAL_TIMEOUT, () -> {
            context.assertTrue(!BrewResidues.onTarget(witch.getUUID(), ally.getUUID()).isEmpty(), "治疗瓶没有真实落地");
            WitchTestSupport.finish(context, witch, ally);
        });
    }

    @GameTest(maxTicks = WitchTestSupport.TIMEOUT, structure = WitchTestSupport.STRUCTURE)
    public void raidDoesNotHealAllyBesideEnemyOrThrowHarmAtAlly(GameTestHelper context) {
        // 同伴挨着其敌人时不上苦黄膏，原版治疗目标也不会误进害药候选。
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.TRUE_QI);
        Pillager ally = ally(context, witch);
        WitchTestSupport.injury(ally, WitchTestSupport.INJURED, WitchTestSupport.INJURED);
        var enemy = WitchTestSupport.enemy(context, witch, WitchTestSupport.FAR);
        enemy.setPos(ally.position().add(ALLY_OFFSET, 0, 0));
        ally.setTarget(enemy);
        witch.setTarget(ally);
        HumanoidWitchAi.decide(witch);
        context.assertTrue(!HumanoidTechniqueHost.hasActiveCast(witch), "贴身同伴仍被施药或成了害药目标");
        WitchTestSupport.finish(context, witch, ally, enemy);
    }

    @GameTest(maxTicks = WitchTestSupport.TIMEOUT, structure = WitchTestSupport.STRUCTURE)
    public void charmImmunityPreventsAnotherCloseBottle(GameTestHelper context) {
        // 用真实喷溅魅惑建立控制与醒后免疫，游炁决策读取 Brewing 的只读查询。
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.WANDERING_QI);
        var enemy = WitchTestSupport.enemy(context, witch, CLOSE);
        exhaustMind(enemy);
        var charm = BrewItemStacks.create(BrewKind.CHARM, BrewQuality.COMMON, BrewUse.SPLASH);
        BrewingRuntime.splash(context.getLevel(), charm, enemy.position(), witch);
        context.runAfterDelay(CHARM_FINISHED, () -> {
            context.assertTrue(BrewQueries.charmImmunityMillis(enemy) > 0, "真实魅惑没有建立醒后免疫");
            witch.setTarget(enemy);
            HumanoidWitchAi.decide(witch);
            var bottles = context.getLevel().getEntitiesOfClass(
                    net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion.class,
                    witch.getBoundingBox().inflate(WitchTestSupport.FAR), potion -> potion.getOwner() == witch);
            context.assertTrue(bottles.isEmpty(), "免疫期还扔魅惑");
            WitchTestSupport.finish(context, witch, enemy);
        });
    }

    @GameTest(maxTicks = WitchTestSupport.TIMEOUT, structure = WitchTestSupport.STRUCTURE)
    public void exhaustedBrainPrioritizesCharmAtRange(GameTestHelper context) {
        // 远处刚耗尽脑力的目标也优先魅惑，不能只实现近身和人数条件。
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.TRUE_QI);
        var enemy = WitchTestSupport.enemy(context, witch, WitchTestSupport.FAR);
        exhaustMind(enemy);
        WitchTestSupport.choose(context, witch, HumanoidWitchAi.LOB, BrewKind.CHARM);
        WitchTestSupport.finish(context, witch, enemy);
    }

    @GameTest(maxTicks = WitchTestSupport.TIMEOUT, structure = WitchTestSupport.STRUCTURE)
    public void naturalWitchBrainStartsAnoint(GameTestHelper context) {
        // 不直接调用决策函数，由真实 serverAiStep 与错相时钟自然起手。
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.TRUE_QI);
        WitchTestSupport.injury(witch, WitchTestSupport.INJURED, 1);
        witch.setNoAi(false /* aiDisabled */);
        context.succeedWhen(() -> {
            context.assertTrue(HumanoidWitchHands.occupied(witch), "自然大脑尚未起行药诀");
            context.assertTrue(BrewItemStacks.kind(witch.getMainHandItem()) == BrewKind.BITTER_YELLOW,
                    "自然大脑药种错误");
            witch.discard();
        });
    }

    private static Pillager ally(GameTestHelper context, Witch witch) {
        // 使用原版 Raid 对象关联同伴，不扩大生产女巫阵营规则。
        Pillager ally = WitchTestSupport.spawn(context, EntityTypes.PILLAGER, HumanoidTier.MORTAL);
        ally.setPos(witch.position().add(0, 0, WitchTestSupport.FAR));
        Raid raid = new Raid(witch.blockPosition(), Difficulty.NORMAL);
        witch.setCurrentRaid(raid);
        ally.setCurrentRaid(raid);
        witch.setTarget(ally);
        return ally;
    }

    private static void exhaustMind(net.minecraft.world.entity.Mob mob) {
        // 只改变实际脑与注意组织疲劳，药效判定和 AI 都从同一身体计算脑力。
        var snapshot = WitchTestSupport.body(mob).toBuilder();
        for (var organ : snapshot.getStructure().getOrgansList()) {
            if (organ.getKind().equals("human:brain")) {
                snapshot.getOrganStatesBuilderList().stream().filter(state -> state.getId().equals(organ.getId()))
                        .forEach(state -> state.setFatigueJoules(
                                organ.getEndurance().getEnduranceJoules() * (1 - LOW_BRAIN_REMAINING)));
            }
        }
        for (var tissue : snapshot.getStructure().getTissuesList()) {
            if (tissue.getId().equals("attention_network")) {
                snapshot.getTissueStatesBuilderList().stream().filter(state -> state.getId().equals(tissue.getId()))
                        .forEach(state -> state.setFatigue(tissue.getFatigueCapacity() * (1 - LOW_BRAIN_REMAINING)));
            }
        }
        WitchTestSupport.restore(mob, snapshot.build());
    }
}
