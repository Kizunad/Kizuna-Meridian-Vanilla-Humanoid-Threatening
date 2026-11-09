package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.brewing.fabric.BrewItemStacks;
import dev.kizuna.meridian.brewing.fabric.BrewResidues;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.server.TechniqueService;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.ItemStack;

/** 主手借用、原版互斥与起手门禁的真实实体生命周期回归。 */
public final class HumanoidWitchLifecycleGameTest {
    private static final int IDLE_OBSERVATION = 1200;
    private static final long STAGGER_MILLIS = 1000;
    private static final double STAGGER_SPEED = 0.5;
    private static final int INTERRUPT_TICK = 4;
    private static final int EXPIRE_BEFORE_EFFECT = 8;

    @GameTest(maxTicks = IDLE_OBSERVATION + 20, structure = WitchTestSupport.STRUCTURE)
    public void idleWitchDoesNotPrepareBody(GameTestHelper context) {
        // 无敌意且不在袭击中的女巫运行一分钟，只能保持未创建身体会话。
        Witch witch = context.spawn(EntityTypes.WITCH, 2, 2, 2);
        witch.setPersistenceRequired();
        context.runAfterDelay(IDLE_OBSERVATION, () -> {
            context.assertTrue(!HumanoidTechniqueHost.hasSession(witch), "闲置女巫创建了身体会话");
            witch.discard();
            context.succeed();
        });
    }

    @GameTest(maxTicks = WitchTestSupport.TIMEOUT, structure = WitchTestSupport.STRUCTURE)
    public void interruptRestoresOriginalAndKeepsBottle(GameTestHelper context) {
        // 走正式受击僵直中断起手，不能只调用持物清理函数冒充中断。
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.TRUE_QI);
        WitchTestSupport.injury(witch, WitchTestSupport.INJURED, 1);
        ItemStack original = witch.getMainHandItem();
        WitchTestSupport.choose(context, witch, HumanoidWitchAi.ANOINT, BrewKind.BITTER_YELLOW);
        ItemStack bottle = witch.getMainHandItem();
        context.runAfterDelay(INTERRUPT_TICK, () -> {
            var stagger = new TechniqueService.Stagger(STAGGER_MILLIS, STAGGER_SPEED);
            TechniqueFabric.service().stagger(witch.getUUID(), stagger);
            context.assertTrue(witch.getMainHandItem() == original && bottle.getCount() == 1, "打断未归还原物");
            WitchTestSupport.finish(context, witch);
        });
    }

    @GameTest(maxTicks = WitchTestSupport.TIMEOUT, structure = WitchTestSupport.STRUCTURE)
    public void deathRestoresBeforeEquipmentDrops(GameTestHelper context) {
        // 真实死亡入口在掉落前撤临时瓶，不依赖实体卸载后的迟到清理。
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.TRUE_QI);
        WitchTestSupport.injury(witch, WitchTestSupport.INJURED, 1);
        ItemStack original = witch.getMainHandItem();
        WitchTestSupport.choose(context, witch, HumanoidWitchAi.ANOINT, BrewKind.BITTER_YELLOW);
        witch.die(witch.damageSources().generic());
        context.assertTrue(!HumanoidWitchHands.occupied(witch), "死亡残留借用主手");
        context.assertTrue(witch.getMainHandItem() == original, "死亡未恢复原物");
        WitchTestSupport.finish(context, witch);
    }

    @GameTest(maxTicks = WitchTestSupport.TIMEOUT, structure = WitchTestSupport.STRUCTURE)
    public void saveAndUnloadRestoreOriginalOnBothObjects(GameTestHelper context) {
        // 先写盘后卸载的真实顺序，重载对象不能留下临时巫药。
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.TRUE_QI);
        WitchTestSupport.injury(witch, WitchTestSupport.INJURED, 1);
        ItemStack original = witch.getMainHandItem();
        WitchTestSupport.choose(context, witch, HumanoidWitchAi.ANOINT, BrewKind.BITTER_YELLOW);
        var saved = HumanoidEntityNbt.save(witch);
        witch.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        context.assertTrue(witch.getMainHandItem() == original, "卸载原对象未恢复主手");
        Witch restored = EntityTypes.WITCH.create(context.getLevel(), EntitySpawnReason.LOAD);
        HumanoidEntityNbt.load(restored, saved);
        context.assertTrue(ItemStack.matches(restored.getMainHandItem(), original), "读档没有恢复原物");
        context.succeed();
    }

    @GameTest(maxTicks = WitchTestSupport.TIMEOUT, structure = WitchTestSupport.STRUCTURE)
    public void vanillaDrinkingPreventsTechniqueAndCastingPreventsThrow(GameTestHelper context) {
        // 双向互斥：原版正在喝时不开功法，功法已起手时直接调用原版投药也不能产生瓶。
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.TRUE_QI);
        WitchTestSupport.injury(witch, WitchTestSupport.INJURED, 1);
        var enemy = WitchTestSupport.enemy(context, witch, WitchTestSupport.NEAR);
        witch.setUsingItem(true /* drinking */);
        HumanoidWitchAi.decide(witch);
        context.assertTrue(!HumanoidTechniqueHost.hasActiveCast(witch), "原版喝药期间起了功法");
        witch.setUsingItem(false /* drinking */);
        WitchTestSupport.choose(context, witch, HumanoidWitchAi.ANOINT, BrewKind.BITTER_YELLOW);
        ItemStack bottle = witch.getMainHandItem();
        witch.performRangedAttack(enemy, 1);
        witch.aiStep();
        context.assertTrue(witch.getMainHandItem() == bottle && !witch.isDrinkingPotion(), "原版自喝占用了功法药瓶");
        context.assertTrue(context.getLevel().getEntitiesOfClass(ThrownSplashPotion.class,
                witch.getBoundingBox().inflate(WitchTestSupport.FAR), potion -> potion.getOwner() == witch).isEmpty(),
                "功法期间原版仍投药");
        WitchTestSupport.finish(context, witch, enemy);
    }

    @GameTest(maxTicks = WitchTestSupport.TIMEOUT, structure = WitchTestSupport.STRUCTURE)
    public void emptyMistRejectsBeforeQiAndWarmup(GameTestHelper context) {
        // 对真实女巫直接发正式施放请求，无残药必须在起手和费用之前拒绝。
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.TRUE_QI);
        ItemStack bottle = BrewItemStacks.create(BrewKind.ARMOR_ROT, BrewQuality.FINE, BrewUse.SPLASH);
        witch.setItemSlot(EquipmentSlot.MAINHAND, bottle);
        var before = WitchTestSupport.body(witch).getMeridianStatesList();
        var request = WitchTestSupport.request(witch, HumanoidWitchAi.MIST);
        var outcome = TechniqueFabric.service().cast(witch.getUUID(), request);
        context.assertTrue(!outcome.hasBinding() && outcome.getReason().equals("16 m 内没有你下的这种药"),
                "无残药拒绝不明确：" + outcome);
        context.assertTrue(before.equals(WitchTestSupport.body(witch).getMeridianStatesList()), "拒绝却扣了真元");
        context.assertTrue(!HumanoidTechniqueHost.hasActiveCast(witch) && witch.getMainHandItem() == bottle,
                "拒绝仍起手或改药瓶");
        WitchTestSupport.finish(context, witch);
    }

    @GameTest(maxTicks = WitchTestSupport.TIMEOUT, structure = WitchTestSupport.STRUCTURE)
    public void residueLostAfterWarmupStillCompletesMist(GameTestHelper context) {
        // 起手时有残药，之后牛奶清掉，效果时刻不退费或倒退取消。
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.TRUE_QI);
        var enemy = WitchTestSupport.enemy(context, witch, WitchTestSupport.NEAR);
        enemy.setItemSlot(EquipmentSlot.CHEST, new ItemStack(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE));
        WitchTestSupport.choose(context, witch, HumanoidWitchAi.FAN, BrewKind.ARMOR_ROT);
        context.runAfterDelay(WitchTestSupport.FAN_END, () -> {
            ItemStack original = witch.getMainHandItem();
            WitchTestSupport.choose(context, witch, HumanoidWitchAi.MIST, BrewKind.ARMOR_ROT);
            context.runAfterDelay(EXPIRE_BEFORE_EFFECT, () -> BrewResidues.clear(enemy.getUUID()));
            context.runAfterDelay(WitchTestSupport.MIST_END, () -> {
                context.assertTrue(!HumanoidTechniqueHost.hasActiveCast(witch)
                        && witch.getMainHandItem() == original, "残药消失后未正常收势恢复");
                WitchTestSupport.finish(context, witch, enemy);
            });
        });
    }
}
