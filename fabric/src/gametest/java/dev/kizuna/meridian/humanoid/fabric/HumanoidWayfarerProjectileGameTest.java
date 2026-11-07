package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LaunchProjectileIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.enchantment.Enchantments;

import static dev.kizuna.meridian.humanoid.fabric.HumanoidGameTestTicks.DEFAULT_TIMEOUT;
import static dev.kizuna.meridian.humanoid.fabric.HumanoidWayfarerTestSupport.*;

/** 生物射击通过正式效果宿主，验证无限弹药、原版弩组件、拉弦系数与互斥。 */
public final class HumanoidWayfarerProjectileGameTest {
    private static final double TARGET_DISTANCE = 12;
    private static final double SEARCH_RADIUS = 32;
    private static final double TOLERANCE = 0.00001;
    private static final int ORIGINAL_DAMAGE = 5;
    private static final int MULTISHOT_COUNT = 3;
    private static final double ARM_FATIGUE_RATIO = 0.5;

    @GameTest(maxTicks = DEFAULT_TIMEOUT, structure = STRUCTURE)
    public void multishotUsesNoAmmoOrDurabilityAndPreservesLoadedCrossbow(GameTestHelper context) {
        // 原上弦光灵箭不参与功法装填，多重射击生成三支不可拾取箭；重复回执不再出箭。
        Pillager mob = pillager(context, HumanoidTier.TRUE_QI);
        target(context, mob, TARGET_DISTANCE);
        ItemStack crossbow = mob.getMainHandItem();
        crossbow.setDamageValue(ORIGINAL_DAMAGE);
        var loaded = ChargedProjectiles.ofNonEmpty(List.of(new ItemStack(Items.SPECTRAL_ARROW)));
        crossbow.set(DataComponents.CHARGED_PROJECTILES, loaded);
        var multishot = context.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.MULTISHOT);
        crossbow.enchant(multishot, 1);
        ItemStack ammunition = new ItemStack(Items.ARROW);
        mob.setItemSlot(EquipmentSlot.OFFHAND, ammunition);
        EffectIntent effect = firstShot(mob, "repeater", Hand.MAIN_HAND);
        submit(mob, effect);
        submit(mob, effect);
        List<AbstractArrow> arrows = arrows(mob);
        context.assertTrue(arrows.size() == MULTISHOT_COUNT, "重复或缺失多重射击箭：" + arrows.size());
        context.assertTrue(arrows.stream().allMatch(arrow -> arrow.pickup == AbstractArrow.Pickup.DISALLOWED),
                "生物箭可被拾取");
        context.assertTrue(ammunition.getCount() == 1, "生物消耗了弹药");
        context.assertTrue(crossbow.getDamageValue() == ORIGINAL_DAMAGE, "生物消耗了耐久");
        context.assertTrue(loaded.equals(crossbow.get(DataComponents.CHARGED_PROJECTILES)), "功法改了原上弦内容");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, structure = STRUCTURE)
    public void offHandBowUsesOtherPhysicalArmAndReadOnlyCoefficient(GameTestHelper context) {
        // 副手弓的拉弦臂为主手侧，疲劳后初速按该臂系数变化，效果不额外记一笔近战做功。
        Pillager mob = pillager(context, HumanoidTier.TRUE_QI);
        target(context, mob, TARGET_DISTANCE);
        mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.BOW));
        double healthy = fireSpeed(mob);
        for (AbstractArrow arrow : arrows(mob)) {
            arrow.discard();
        }
        fatigueRightArm(mob);
        long revision = body(mob).revision();
        double coefficient = VanillaDamageFabric.armCoefficient(mob, HumanoidArm.RIGHT);
        double tired = fireSpeed(mob);
        context.assertTrue(coefficient < 1, "拉弦臂疲劳没有降低系数");
        context.assertTrue(Math.abs(tired / healthy - coefficient) < TOLERANCE, "副手弓没有使用另一臂系数");
        context.assertTrue(body(mob).revision() == revision, "射击系数查询额外修改了身体");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, structure = STRUCTURE)
    public void castingBlocksVanillaUseAndAttackWithoutClearingLoadedAmmo(GameTestHelper context) {
        // 真实起手后直接调用两个原版入口，验证同一 tick 内也不会另射或清掉原上弦内容。
        Pillager mob = pillager(context, HumanoidTier.TRUE_QI);
        var target = target(context, mob, TARGET_DISTANCE);
        var loaded = ChargedProjectiles.ofNonEmpty(List.of(new ItemStack(Items.ARROW)));
        mob.getMainHandItem().set(DataComponents.CHARGED_PROJECTILES, loaded);
        var request = CastRequest.newBuilder()
                .setBodyId(body(mob).id().value())
                .setTechnique(reference(mob, "repeater"))
                .setTargetId(target.getStringUUID())
                .setCastId(UUID.randomUUID().toString())
                .build();
        var outcome = TechniqueFabric.service().cast(mob.getUUID(), request);
        context.assertTrue(outcome.hasBinding(), "行矢没有起手：" + outcome);
        context.assertTrue(HumanoidBrain.blocksRanged(mob), "起手后未屏蔽原版射击");
        mob.startUsingItem(InteractionHand.MAIN_HAND);
        mob.performRangedAttack(target, 1);
        context.assertTrue(!mob.isUsingItem() && arrows(mob).isEmpty(), "原版在功法期间使用了弩");
        context.assertTrue(loaded.equals(mob.getMainHandItem().get(DataComponents.CHARGED_PROJECTILES)),
                "原版入口消费了上弦内容");
        HumanoidTechniqueHost.removed(mob);
        context.assertTrue(!HumanoidBrain.blocksRanged(mob), "卸载清理后仍屏蔽原版使用");
        context.assertTrue(!TechniqueState.read(body(mob)).hasActiveCast(), "恢复清理后活动仍残留");
        context.succeed();
    }

    private static double fireSpeed(Pillager mob) {
        // 满月散布为零，读取刚生成的原版箭速度。
        EffectIntent effect = firstShot(mob, "full_draw", Hand.OFF_HAND);
        submit(mob, effect);
        return arrows(mob).getFirst().getDeltaMovement().length();
    }

    private static void submit(Pillager mob, EffectIntent effect) {
        // 请求与效果身份固定；相同意图重放必须被宿主回执去重。
        HumanoidTechniqueHost.prepare(mob).effects().submit(new TechniqueHost.EffectSubmission(
                effect.getCastId() + "_shot", effect));
    }

    private static EffectIntent firstShot(Pillager mob, String technique, Hand hand) {
        // 从正式目录读取首发参数和书级规则，测试不改任何射击倍率。
        var definition = definition(mob, technique);
        var shot = definition.getEffectsList().stream().filter(TechniqueEffect::hasLaunchProjectile)
                .findFirst().orElseThrow();
        var reference = reference(mob, technique);
        var book = TechniqueFabric.service().registry().bookOf(reference).orElseThrow();
        long now = body(mob).settledAt().elapsedMillis();
        var intent = LaunchProjectileIntent.newBuilder()
                .setProjectile(shot.getLaunchProjectile())
                .setHeldItemRules(book.getHeldItemRules())
                .setCastStartedMillis(now)
                .setClipStartMillis(0)
                .build();
        return EffectIntent.newBuilder()
                .setCastId(UUID.randomUUID().toString())
                .setEffectId("first_shot")
                .setSelectedHand(hand)
                .setScheduledMillis(now + shot.getAtMillis())
                .setTargetId(mob.getTarget().getStringUUID())
                .setLockTarget(false /* lockTarget */)
                .setLaunchProjectile(intent)
                .build();
    }

    private static List<AbstractArrow> arrows(Pillager mob) {
        // 只读本射手的实体，其他并行 GameTest 的箭不影响数量。
        return mob.level().getEntitiesOfClass(AbstractArrow.class, mob.getBoundingBox().inflate(SEARCH_RADIUS),
                arrow -> arrow.getOwner() == mob);
    }

    private static void fatigueRightArm(Pillager mob) {
        // 按真实解剖标签给右臂三肌注入疲劳，保留身体其余内容并经 Framework 恢复校验。
        var snapshot = HumanoidTechniqueHost.prepare(mob).body().snapshot();
        var tired = snapshot.toBuilder();
        for (int index = 0; index < snapshot.getStructure().getTissuesCount(); index++) {
            var tissue = snapshot.getStructure().getTissues(index);
            if (tissue.getTagsList().contains("human:arm_muscle")
                    && tissue.getId().startsWith("right_")) {
                double fatigue = tissue.getFatigueCapacity() * ARM_FATIGUE_RATIO;
                tired.getTissueStatesBuilder(index).setFatigue(fatigue);
            }
        }
        restoreBody(mob, tired.build());
    }
}
