package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.brewing.fabric.BrewItemStacks;
import dev.kizuna.meridian.brewing.fabric.BrewResidues;
import dev.kizuna.meridian.brewing.server.BrewBodyProgress;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.technique.server.TechniqueState;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** 真实女巫通过正式选式、学卷、费用与用药链执行行药诀。 */
public final class HumanoidWitchDecisionGameTest {
    private static final double RUN_SPEED = 0.12;
    private static final double CLOSE = 1.5;
    private static final double OUTSIDE_FAN = 3.8;
    private static final double CROWD_SIDE = 0.6;

    @GameTest(maxTicks = WitchTestSupport.TIMEOUT, structure = WitchTestSupport.STRUCTURE)
    public void wanderingAnointsArmsAndRestoresOriginal(GameTestHelper context) {
        // 手经、上卷、寻常品质、实际药效和同一原物恢复在同一条链上验证。
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.WANDERING_QI);
        WitchTestSupport.injury(witch, WitchTestSupport.INJURED, 1);
        ItemStack original = witch.getMainHandItem();
        WitchTestSupport.choose(context, witch, HumanoidWitchAi.ANOINT, BrewKind.BITTER_YELLOW);
        ItemStack bottle = witch.getMainHandItem();
        context.assertTrue(BrewItemStacks.quality(bottle) == BrewQuality.COMMON, "游炁没有用寻常药");
        context.runAfterDelay(WitchTestSupport.ANOINT_EFFECT, () -> {
            var doses = BrewBodyProgress.read(WitchTestSupport.body(witch)).getDosesList();
            context.assertTrue(!doses.isEmpty(), "敷药没有写入身体");
            context.assertTrue(bottle.getCount() == 1, "女巫扣了药瓶");
        });
        context.runAfterDelay(WitchTestSupport.ANOINT_END, () -> {
            context.assertTrue(witch.getMainHandItem() == original, "结束未恢复同一原物");
            WitchTestSupport.finish(context, witch);
        });
    }

    @GameTest(maxTicks = WitchTestSupport.TIMEOUT, structure = WitchTestSupport.STRUCTURE)
    public void wanderingDrinksOnlyWhenAllLimbsHurtAndSafe(GameTestHelper context) {
        // 四肢 65% 命中服药条件，腿经实际付款，禁止聚气汤替代苦黄膏。
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.WANDERING_QI);
        WitchTestSupport.injury(witch, WitchTestSupport.DRINK_INJURED, WitchTestSupport.DRINK_INJURED);
        WitchTestSupport.choose(context, witch, HumanoidWitchAi.IMBIBE, BrewKind.BITTER_YELLOW);
        context.runAfterDelay(WitchTestSupport.IMBIBE_EFFECT, () -> {
            var state = BrewBodyProgress.read(WitchTestSupport.body(witch));
            context.assertTrue(state.getDosesCount() > 0 && state.getNauseaUntilMillis() > 0, "服药未生效或无反胃");
            WitchTestSupport.finish(context, witch);
        });
    }

    @GameTest(maxTicks = WitchTestSupport.TIMEOUT, structure = WitchTestSupport.STRUCTURE)
    public void nearbyEnemyPreventsImbibeAndWanderingNeverSeeds(GameTestHelper context) {
        // 上卷在敌人附近不服药，未学中卷时不会凭空使用掷、泼、噀。
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.WANDERING_QI);
        WitchTestSupport.injury(witch, WitchTestSupport.DRINK_INJURED, WitchTestSupport.DRINK_INJURED);
        var enemy = WitchTestSupport.enemy(context, witch, WitchTestSupport.NEAR);
        HumanoidWitchAi.decide(witch);
        var progress = TechniqueState.read(BodyProtobuf.fromMessage(WitchTestSupport.body(witch)));
        context.assertTrue(!progress.hasActiveCast(), "有敌人时误服药或游炁越卷施放");
        context.assertTrue(progress.getKnownList().stream()
                .noneMatch(known -> known.getTechnique().getId().equals(HumanoidWitchAi.LOB)), "游炁学了中卷");
        WitchTestSupport.finish(context, witch, enemy);
    }

    @GameTest(maxTicks = WitchTestSupport.TIMEOUT, structure = WitchTestSupport.STRUCTURE)
    public void trueQiApproachingHeavyArmorUsesRotAtTorso(GameTestHelper context) {
        // 重甲逼近选择蚀甲，出手前实际朝向躯干并创建精良喷溅瓶。
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.TRUE_QI);
        var enemy = WitchTestSupport.enemy(context, witch, WitchTestSupport.FAR);
        enemy.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        enemy.setDeltaMovement(new Vec3(0, 0, -RUN_SPEED));
        WitchTestSupport.choose(context, witch, HumanoidWitchAi.LOB, BrewKind.ARMOR_ROT);
        context.assertTrue(BrewItemStacks.quality(witch.getMainHandItem()) == BrewQuality.FINE, "真炁品质不是精良");
        enemy.setDeltaMovement(Vec3.ZERO);
        context.runAfterDelay(WitchTestSupport.THROW_EFFECT, () -> {
            assertProjectile(context, witch, BrewKind.ARMOR_ROT);
            WitchTestSupport.finish(context, witch, enemy);
        });
    }

    @GameTest(maxTicks = WitchTestSupport.TIMEOUT, structure = WitchTestSupport.STRUCTURE)
    public void runningAwayUsesDepletionAtLegs(GameTestHelper context) {
        // 目标拉开距离时用枯竭瞄腿，原版药瓶继续真实飞行。
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.TRUE_QI);
        var enemy = WitchTestSupport.enemy(context, witch, WitchTestSupport.FAR);
        enemy.setDeltaMovement(new Vec3(0, 0, RUN_SPEED));
        WitchTestSupport.choose(context, witch, HumanoidWitchAi.LOB, BrewKind.DEPLETION);
        enemy.setDeltaMovement(Vec3.ZERO);
        context.runAfterDelay(WitchTestSupport.THROW_EFFECT, () -> {
            assertProjectile(context, witch, BrewKind.DEPLETION);
            context.assertTrue(witch.getXRot() > 0, "瞄腿没有俯视");
            WitchTestSupport.finish(context, witch, enemy);
        });
    }

    @GameTest(maxTicks = WitchTestSupport.TIMEOUT, structure = WitchTestSupport.STRUCTURE)
    public void nearbyInsectsSelectLure(GameTestHelper context) {
        // 光照不确定时用真实蜘蛛形成招虫条件，选式仍由生产环境快照判定。
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.TRUE_QI);
        var enemy = WitchTestSupport.enemy(context, witch, WitchTestSupport.FAR);
        var spider = context.spawn(EntityTypes.SPIDER, 1, 2, 1);
        spider.setNoAi(true /* aiDisabled */);
        WitchTestSupport.choose(context, witch, HumanoidWitchAi.LOB, BrewKind.INSECT_LURE);
        context.runAfterDelay(WitchTestSupport.THROW_EFFECT, () -> {
            assertProjectile(context, witch, BrewKind.INSECT_LURE);
            WitchTestSupport.finish(context, witch, enemy, spider);
        });
    }

    @GameTest(maxTicks = WitchTestSupport.TIMEOUT, structure = WitchTestSupport.STRUCTURE)
    public void fanSeedsThenEnoughResidueTriggers(GameTestHelper context) {
        // 三米用泼引，效果真正留下残药后噀催同种药，再检查实际移除。
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.TRUE_QI);
        var enemy = WitchTestSupport.enemy(context, witch, WitchTestSupport.NEAR);
        enemy.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        WitchTestSupport.choose(context, witch, HumanoidWitchAi.FAN, BrewKind.ARMOR_ROT);
        context.runAfterDelay(WitchTestSupport.FAN_END, () -> {
            context.assertTrue(!BrewResidues.onTarget(witch.getUUID(), enemy.getUUID()).isEmpty(), "泼引没有残药");
            WitchTestSupport.choose(context, witch, HumanoidWitchAi.MIST, BrewKind.ARMOR_ROT);
            context.runAfterDelay(WitchTestSupport.MIST_EFFECT, () -> {
                context.assertTrue(BrewResidues.onTarget(witch.getUUID(), enemy.getUUID()).isEmpty(), "噀催没有清残药");
                WitchTestSupport.finish(context, witch, enemy);
            });
        });
    }

    @GameTest(maxTicks = WitchTestSupport.TIMEOUT, structure = WitchTestSupport.STRUCTURE)
    public void outsideFanAndBelowThrowRangeDoesNotCast(GameTestHelper context) {
        // 3.5–4 米的边界空档不能偷用泼引。
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.TRUE_QI);
        var enemy = WitchTestSupport.enemy(context, witch, OUTSIDE_FAN);
        HumanoidWitchAi.decide(witch);
        context.assertTrue(!HumanoidTechniqueHost.hasActiveCast(witch), "超出泼引距离仍起手");
        WitchTestSupport.finish(context, witch, enemy);
    }

    @GameTest(maxTicks = WitchTestSupport.TIMEOUT, structure = WitchTestSupport.STRUCTURE)
    public void wanderingCloseCharmUsesOrdinaryProjectileWithoutSeed(GameTestHelper context) {
        // 游炁近身魅惑使用普通喷溅实体，始终不登记功法残药。
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.WANDERING_QI);
        var enemy = WitchTestSupport.enemy(context, witch, CLOSE);
        HumanoidWitchAi.decide(witch);
        assertProjectile(context, witch, BrewKind.CHARM);
        context.assertTrue(!HumanoidTechniqueHost.hasActiveCast(witch), "游炁魅惑误起中卷");
        context.runAfterDelay(WitchTestSupport.THROW_END, () -> {
            context.assertTrue(BrewResidues.onTarget(witch.getUUID(), enemy.getUUID()).isEmpty(), "直接扔魅惑下了残药");
            WitchTestSupport.finish(context, witch, enemy);
        });
    }

    @GameTest(maxTicks = WitchTestSupport.TIMEOUT, structure = WitchTestSupport.STRUCTURE)
    public void trueQiSurroundedUsesCharm(GameTestHelper context) {
        // 两名已有敌意的实体构成围攻，掷引魅惑出手。
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.TRUE_QI);
        var first = WitchTestSupport.enemy(context, witch, WitchTestSupport.NEAR);
        var second = WitchTestSupport.enemy(context, witch, WitchTestSupport.NEAR);
        second.setPos(first.position().add(CROWD_SIDE, 0, 0));
        first.setTarget(witch);
        WitchTestSupport.choose(context, witch, HumanoidWitchAi.LOB, BrewKind.CHARM);
        context.runAfterDelay(WitchTestSupport.THROW_EFFECT, () -> {
            assertProjectile(context, witch, BrewKind.CHARM);
            WitchTestSupport.finish(context, witch, first, second);
        });
    }

    private static void assertProjectile(GameTestHelper context, Witch witch, BrewKind kind) {
        // 检查真实世界中的原版喷溅药瓶及其所有者和物品组件。
        var potions = context.getLevel().getEntitiesOfClass(ThrownSplashPotion.class,
                witch.getBoundingBox().inflate(WitchTestSupport.FAR), potion -> potion.getOwner() == witch);
        context.assertTrue(potions.stream().anyMatch(potion -> BrewItemStacks.isBrew(potion.getItem())
                && BrewItemStacks.kind(potion.getItem()) == kind), "没有实际投出 " + kind);
    }
}
