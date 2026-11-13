package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.brewing.fabric.BrewItemStacks;
import dev.kizuna.meridian.brewing.fabric.BrewResidues;
import dev.kizuna.meridian.brewing.fabric.BrewingRuntime;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.target.NearestHealableRaiderTargetGoal;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potions;

/** 原版条件与两声额外声音通过真实女巫、原版方法和网络包验证。 */
public final class HumanoidWitchVanillaGameTest {
    private static final int DRINK_ATTEMPTS = 100;
    private static final int FIRE_TICKS = 100;
    private static final double SLOWNESS_DISTANCE = 9;
    private static final int LAUGH_DELAY = 10;
    private static final int MAX_SOUND_WAIT = 60;
    private static final Map<UUID, Long> triggered = new HashMap<>();

    static {
        BrewResidues.TRIGGERED.register((source, kind, count) ->
                triggered.put(source.getUUID(), source.level().getGameTime()));
    }

    @GameTest(maxTicks = WitchTestSupport.TIMEOUT, structure = WitchTestSupport.STRUCTURE)
    public void mortalAndCultivatorKeepVanillaThrowAndFireDrink(GameTestHelper context) {
        // 两档分别按原版九格缓慢条件投药、着火概率自喝，不用自建药效替代。
        for (HumanoidTier tier : new HumanoidTier[] { HumanoidTier.MORTAL, HumanoidTier.TRUE_QI }) {
            Witch witch = WitchTestSupport.witch(context, tier);
            var enemy = WitchTestSupport.enemy(context, witch, SLOWNESS_DISTANCE);
            witch.performRangedAttack(enemy, 1);
            var bottles = context.getLevel().getEntitiesOfClass(ThrownSplashPotion.class,
                    witch.getBoundingBox().inflate(SLOWNESS_DISTANCE), potion -> potion.getOwner() == witch);
            context.assertTrue(bottles.size() == 1 && bottles.getFirst().getItem()
                    .get(DataComponents.POTION_CONTENTS).is(Potions.SLOWNESS), "原版缓慢投药条件失效：" + tier);
            witch.setRemainingFireTicks(FIRE_TICKS);
            for (int attempt = 0; attempt < DRINK_ATTEMPTS && !witch.isDrinkingPotion(); attempt++) {
                witch.aiStep();
            }
            context.assertTrue(witch.isDrinkingPotion() && witch.getMainHandItem()
                    .get(DataComponents.POTION_CONTENTS).is(Potions.FIRE_RESISTANCE), "原版抗火自喝失效：" + tier);
            context.assertTrue(!HumanoidTechniqueHost.hasActiveCast(witch), "原版本事误扣功法费用");
            bottles.forEach(ThrownSplashPotion::discard);
            witch.discard();
            enemy.discard();
        }
        context.succeed();
    }

    @GameTest(maxTicks = WitchTestSupport.TIMEOUT, structure = WitchTestSupport.STRUCTURE)
    public void nauseaCancelsDrinkButStillDecrementsRaidHealingCooldown(GameTestHelper context)
            throws ReflectiveOperationException {
        // 读原版 Goal 的真实冷却，中止自喝不能跳过女巫自己的 aiStep 前半段。
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.TRUE_QI);
        var field = Witch.class.getDeclaredField("healRaidersGoal");
        field.setAccessible(true /* accessible */);
        var goal = (NearestHealableRaiderTargetGoal<?>) field.get(witch);
        goal.start();
        int before = goal.getCooldown();
        witch.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.POTION));
        witch.setUsingItem(true /* drinking */);
        var bitter = BrewItemStacks.create(BrewKind.BITTER_YELLOW, BrewQuality.COMMON, BrewUse.DRINK);
        BrewingRuntime.consume(witch.level(), witch, bitter);
        witch.aiStep();
        context.assertTrue(!witch.isDrinkingPotion(), "反胃没有中止原版自喝");
        context.assertTrue(goal.getCooldown() == before - 1, "反胃跳过了同伴治疗冷却递减");
        WitchTestSupport.finish(context, witch);
    }

    @GameTest(maxTicks = WitchTestSupport.TIMEOUT, structure = WitchTestSupport.STRUCTURE)
    public void imbibeAddsHostileWitchDrinkSound(GameTestHelper context) {
        // 在功法目录原有喝药音之外收到一次原版女巫喝药声。
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.TRUE_QI);
        WitchTestSupport.injury(witch, WitchTestSupport.DRINK_INJURED, WitchTestSupport.DRINK_INJURED);
        var listener = SoundListener.create(witch);
        WitchTestSupport.choose(context, witch, HumanoidWitchAi.IMBIBE, BrewKind.BITTER_YELLOW);
        var sounds = listener.sounds(witch, "minecraft:entity.witch.drink");
        context.assertTrue(sounds.size() == 1 && sounds.getFirst().getSource() == SoundSource.HOSTILE,
                "服药额外声音缺失、重复或类别错误：witch=" + witch.position() + " " + listener.describe());
        listener.close();
        WitchTestSupport.finish(context, witch);
    }

    @GameTest(maxTicks = WitchTestSupport.TIMEOUT, structure = WitchTestSupport.STRUCTURE)
    public void successfulMistLaughsHalfSecondLater(GameTestHelper context) {
        // 噀催效果真正消费残药以后，从事件时刻到收到声音包精确核对十 tick。
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.TRUE_QI);
        var enemy = WitchTestSupport.enemy(context, witch, WitchTestSupport.NEAR);
        enemy.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        WitchTestSupport.choose(context, witch, HumanoidWitchAi.FAN, BrewKind.ARMOR_ROT);
        context.runAfterDelay(WitchTestSupport.FAN_END, () -> {
            var listener = SoundListener.create(witch);
            WitchTestSupport.choose(context, witch, HumanoidWitchAi.MIST, BrewKind.ARMOR_ROT);
            observeLaugh(context, witch, enemy, listener, 0);
        });
    }

    private static void observeLaugh(GameTestHelper context, Witch witch, net.minecraft.world.entity.Mob enemy,
                                     SoundListener listener, int elapsed) {
        // 逐 tick 只观察实际声音包，不调用生产播声方法或伪造催发事件。
        var sounds = listener.sounds(witch, "minecraft:entity.witch.celebrate");
        if (!sounds.isEmpty()) {
            long triggeredAt = triggered.remove(witch.getUUID());
            long delay = witch.level().getGameTime() - triggeredAt;
            context.assertTrue(delay >= LAUGH_DELAY && delay <= LAUGH_DELAY + 1, "笑声延迟不符：" + delay);
            context.assertTrue(sounds.size() == 1 && sounds.getFirst().getSource() == SoundSource.HOSTILE,
                    "噀催笑声重复或类别错误");
            listener.close();
            WitchTestSupport.finish(context, witch, enemy);
            return;
        }
        context.assertTrue(elapsed < MAX_SOUND_WAIT, "成功噀催没有笑声：witch=" + witch.position() + " "
                + listener.describe());
        context.runAfterDelay(1, () -> observeLaugh(context, witch, enemy, listener, elapsed + 1));
    }
}
