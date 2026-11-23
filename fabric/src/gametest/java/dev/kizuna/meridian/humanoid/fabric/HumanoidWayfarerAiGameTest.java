package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.Decision;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.inventory.MobInventories;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.server.TechniqueState;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.phys.Vec3;

import static dev.kizuna.meridian.humanoid.fabric.HumanoidGameTestTicks.DEFAULT_TIMEOUT;
import static dev.kizuna.meridian.humanoid.fabric.HumanoidWayfarerTestSupport.*;

/** 真实行矢目录上的处境选择与收势换兵，不复制功法库承接窗口公式。 */
public final class HumanoidWayfarerAiGameTest {
    private static final double FAR = 20;
    private static final double MEDIUM = 12;
    private static final double CLOSE = 2;
    private static final double APPROACH_SPEED = -0.1;
    private static final int WALL_Z = 8;
    private static final int WALL_HEIGHT = 3;
    private static final int REPEATER_CHAIN_TICK = 84;
    private static final int FULL_DRAW_CHAIN_TICK = 98;
    private static final long FULL_DRAW_ENTRY_MILLIS = 400;
    private static final long CONVERGENCE_ENTRY_MILLIS = 1200;
    private static final int ORIGINAL_CROSSBOW_DAMAGE = 7;
    private static final int CROSSBOW_RESTORE_TIMEOUT = 180;
    private static final String ORIGINAL_CROSSBOW_NAME = "收势还手测试弩";

    @GameTest(maxTicks = DEFAULT_TIMEOUT, structure = STRUCTURE)
    public void visibleDistanceBandsAndHeldWeaponChooseTheirTechniques(GameTestHelper context) {
        // 背囊里的弓参与候选预览，查询分数本身不改变主手弩。
        Pillager mob = pillager(context, HumanoidTier.TRUE_QI);
        Cow target = target(context, mob, FAR);
        assertChosen(context, mob, target, Set.of("kneeling", "full_draw"));
        target.setPos(mob.position().add(0, 0, MEDIUM));
        assertChosen(context, mob, target, Set.of("repeater", "pearls"));
        target.setPos(mob.position().add(0, 0, CLOSE));
        assertChosen(context, mob, target, Set.of("gale"));
        HumanoidWayfarerWeapons.reserve(mob, HumanoidWayfarerWeapons.BOWS).orElseThrow().apply();
        assertChosen(context, mob, target, Set.of("bow_strike"));
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, structure = STRUCTURE)
    public void hiddenRememberedTargetChoosesArrowRainAndNeverTraining(GameTestHelper context) {
        // 隔墙目标仍是原版已知对手，事实与候选应允许抛射且不把训练式当战斗动作。
        Pillager mob = pillager(context, HumanoidTier.TRUE_QI);
        TechniqueFabric.service().learn(mob.getUUID(), PREFIX + "bow_lower");
        Cow target = target(context, mob, MEDIUM);
        for (int x = ORIGIN - 1; x <= ORIGIN + 1; x++) {
            for (int y = ORIGIN; y <= ORIGIN + WALL_HEIGHT; y++) {
                context.setBlock(new BlockPos(x, y, WALL_Z), Blocks.STONE);
            }
        }
        var facts = HumanoidWayfarerFacts.read(mob, target);
        context.assertTrue(!facts.visible(), "测试墙没有遮住目标");
        assertChosen(context, mob, target, Set.of("arrow_rain"));
        context.assertTrue(!HumanoidWayfarerAi.allowed(mob, body(mob), definition(mob, "dry_draw"),
                Optional.of(facts)), "训练式进入了 AI 候选");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, structure = STRUCTURE)
    public void approachingFactIncludesStationaryShooter(GameTestHelper context) {
        // 自己不动、敌人冲来就算被逼近；敌人离开则不算，目标速度按水平米每秒回传。
        Pillager mob = pillager(context, HumanoidTier.TRUE_QI);
        Cow target = target(context, mob, MEDIUM);
        target.setDeltaMovement(new Vec3(0, 0, APPROACH_SPEED));
        var approaching = HumanoidWayfarerFacts.read(mob, target);
        context.assertTrue(approaching.approaching(), "站定射手漏报逼近");
        context.assertTrue(approaching.speedMetersPerSecond() > 0, "目标速度没有回传");
        target.setDeltaMovement(new Vec3(0, 0, -APPROACH_SPEED));
        context.assertTrue(!HumanoidWayfarerFacts.read(mob, target).approaching(), "离开的目标误报逼近");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, structure = STRUCTURE)
    public void rejectedCastRestoresInventoryExchange(GameTestHelper context) {
        // 候选查询后另一手出现非弹药，真实起手必须拒绝并还原原弩与背囊弓。
        Pillager mob = pillager(context, HumanoidTier.TRUE_QI);
        Cow target = target(context, mob, FAR);
        var snapshot = body(mob);
        var opponent = new HumanoidOpponent(target, Optional.empty());
        var readout = HumanoidRepertoires.read(mob, snapshot, Optional.of(opponent), ignored -> false);
        var exchange = readout.exchanges().get(PREFIX + "full_draw");
        context.assertTrue(exchange != null, "没有满月的备用弓候选");
        var original = mob.getMainHandItem();
        mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.STONE));
        var mind = new HumanoidMind();
        mind.engage(target);
        var turn = new HumanoidActions.Turn(mob, mind, snapshot, readout, Optional.of(opponent));
        HumanoidActions.execute(turn, Decision.cast(Decision.Action.STRIKE, PREFIX + "full_draw", "持物改变后起手"));
        context.assertTrue(!TechniqueState.read(body(mob)).hasActiveCast(), "非法另一手仍成功起手");
        context.assertTrue(ItemStack.isSameItemSameComponents(mob.getMainHandItem(), original),
                "起手被拒后没有还原原弩");
        context.assertTrue(ItemStack.isSameItemSameComponents(
                MobInventories.inventory(mob).peek(exchange.slot()), exchange.replacement()), "背囊弓没有还原");
        context.succeed();
    }

    @GameTest(maxTicks = CROSSBOW_RESTORE_TIMEOUT, structure = STRUCTURE)
    public void completedCastRestoresLoadedCrossbowAndComponents(GameTestHelper context) {
        // 成功施放收势后，原弩的耐久、名称和装填内容须随主手一起还原。
        Pillager mob = pillager(context, HumanoidTier.TRUE_QI);
        prepareQi(context, mob);
        clearKnownTechniques(mob);
        TechniqueFabric.service().learn(mob.getUUID(), PREFIX + "bow_upper");
        TechniqueFabric.service().learn(mob.getUUID(), PREFIX + "repeater");
        Cow target = target(context, mob, FAR);
        ItemStack crossbow = mob.getMainHandItem().copy();
        crossbow.setDamageValue(ORIGINAL_CROSSBOW_DAMAGE);
        crossbow.set(DataComponents.CUSTOM_NAME, Component.literal(ORIGINAL_CROSSBOW_NAME));
        ChargedProjectiles charged = ChargedProjectiles.ofNonEmpty(List.of(new ItemStack(Items.SPECTRAL_ARROW)));
        crossbow.set(DataComponents.CHARGED_PROJECTILES, charged);
        mob.setItemSlot(EquipmentSlot.MAINHAND, crossbow.copy());
        var bowExchange = HumanoidWayfarerWeapons.reserve(mob, HumanoidWayfarerWeapons.BOWS).orElseThrow();
        executeChoice(context, mob, target, "full_draw");
        context.assertTrue(TechniqueState.read(body(mob)).hasActiveCast(), "满月没有成功起手");
        var expected = new CrossbowRestore(crossbow, charged, bowExchange.slot());
        awaitCrossbowRestore(context, mob, expected, CROSSBOW_RESTORE_TIMEOUT);
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, structure = STRUCTURE)
    public void repeaterRecoverySwapsInventoryBowAndStartsFullDrawAtEntry(GameTestHelper context) {
        // 连弩收势后半段由大脑候选发现弓式，真实交换后走同一施放入口承接。
        Pillager mob = pillager(context, HumanoidTier.TRUE_QI);
        Cow target = target(context, mob, MEDIUM);
        var crossbow = mob.getMainHandItem();
        cast(context, mob, target, "repeater");
        context.runAtTickTime(REPEATER_CHAIN_TICK, () -> {
            // 前式箭的击退会留下速度；满月候选要求远处静止目标。
            target.setPos(mob.position().add(0, 0, FAR));
            target.setDeltaMovement(Vec3.ZERO);
            executeChoice(context, mob, target, "full_draw");
            var active = TechniqueState.read(body(mob)).getActiveCast();
            context.assertTrue(active.getRequest().getTechnique().getId().equals(PREFIX + "full_draw"), "换兵承接未起手");
            context.assertTrue(active.getClipStartMillis() == FULL_DRAW_ENTRY_MILLIS, "满月未跳过预备段");
            context.assertTrue(HumanoidWayfarerWeapons.holds(mob, HumanoidWayfarerWeapons.BOWS), "没有换出备用弓");
            var reserve = HumanoidWayfarerWeapons.reserve(mob, HumanoidWayfarerWeapons.CROSSBOWS).orElseThrow();
            context.assertTrue(ItemStack.isSameItemSameComponents(reserve.replacement(), crossbow),
                    "原弩丢失或被复制");
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, structure = STRUCTURE)
    public void convergenceOnlyStartsFromFullDrawFollowUp(GameTestHelper context) {
        // 未有前式时天地同至拒绝；满月收势窗口内才起手并采用 1.2 秒入口。
        Pillager mob = pillager(context, HumanoidTier.TRUE_QI);
        TechniqueFabric.service().learn(mob.getUUID(), PREFIX + "bow_lower");
        HumanoidWayfarerWeapons.reserve(mob, HumanoidWayfarerWeapons.BOWS).orElseThrow().apply();
        Cow target = target(context, mob, FAR);
        var refused = TechniqueFabric.service().cast(mob.getUUID(), request(mob, target, "convergence"));
        context.assertTrue(refused.getFailure() == TechniqueFailure.FOLLOW_UP_REQUIRED, "天地同至可以普通起手");
        cast(context, mob, target, "full_draw");
        context.runAtTickTime(FULL_DRAW_CHAIN_TICK, () -> {
            executeChoice(context, mob, target, "convergence");
            var active = TechniqueState.read(body(mob)).getActiveCast();
            context.assertTrue(active.getRequest().getTechnique().getId().equals(PREFIX + "convergence"), "天地同至未承接起手");
            context.assertTrue(active.getClipStartMillis() == CONVERGENCE_ENTRY_MILLIS, "天地同至起播点错误");
            context.succeed();
        });
    }

    private static void executeChoice(GameTestHelper context, Pillager mob, LivingEntity target, String technique) {
        // 与大脑执行相同：读候选库存、通过动作执行器换兵、再发真实施放请求。
        var snapshot = body(mob);
        var opponent = new HumanoidOpponent(target, Optional.empty());
        var readout = HumanoidRepertoires.read(mob, snapshot, Optional.of(opponent), ignored -> false);
        context.assertTrue(readout.strikes().stream()
                        .anyMatch(known -> known.getTechnique().getId().equals(PREFIX + technique)),
                "承接候选缺失：" + technique + "，目标事实：" + HumanoidWayfarerFacts.read(mob, target));
        var mind = new HumanoidMind();
        mind.engage(target);
        var turn = new HumanoidActions.Turn(mob, mind, snapshot, readout, Optional.of(opponent));
        HumanoidActions.execute(turn, Decision.cast(Decision.Action.STRIKE, PREFIX + technique, "测试真实行矢施放"));
    }

    private static void awaitCrossbowRestore(GameTestHelper context, Pillager mob, CrossbowRestore expected,
                                             int remainingTicks) {
        var progress = TechniqueState.read(body(mob));
        if (!progress.hasActiveCast()) {
            context.assertTrue(progress.hasLastCast()
                            && progress.getLastCast().getTechnique().getId().equals(PREFIX + "full_draw"),
                    "没有观察到满月施放收势");
            context.runAfterDelay(1, () -> assertCrossbowRestored(context, mob, expected));
            return;
        }
        context.assertTrue(remainingTicks > 0, "满月没有在测试时限内收势");
        context.runAfterDelay(1, () -> awaitCrossbowRestore(context, mob, expected, remainingTicks - 1));
    }

    private static void assertCrossbowRestored(GameTestHelper context, Pillager mob, CrossbowRestore expected) {
        ItemStack restoredCrossbow = mob.getMainHandItem();
        context.assertTrue(ItemStack.isSameItemSameComponents(restoredCrossbow, expected.crossbow()),
                "收势后原弩或组件没有恢复");
        context.assertTrue(restoredCrossbow.getDamageValue() == ORIGINAL_CROSSBOW_DAMAGE,
                "收势后原弩耐久改变");
        context.assertTrue(expected.charged().equals(restoredCrossbow.get(DataComponents.CHARGED_PROJECTILES)),
                "收势后原弩装填内容改变");
        context.assertTrue(restoredCrossbow.get(DataComponents.CUSTOM_NAME).getString()
                        .equals(ORIGINAL_CROSSBOW_NAME),
                "收势后原弩名称组件改变");
        context.assertTrue(HumanoidWayfarerWeapons.holds(mob, HumanoidWayfarerWeapons.CROSSBOWS),
                "收势后主手没有恢复原弩");
        context.assertTrue(ItemStack.isSameItemSameComponents(
                MobInventories.inventory(mob).peek(expected.bowSlot()), new ItemStack(Items.BOW)),
                "收势后背囊没有保留备用弓");
        context.succeed();
    }

    private record CrossbowRestore(ItemStack crossbow, ChargedProjectiles charged, int bowSlot) {
    }

    private static void cast(GameTestHelper context, Pillager mob, LivingEntity target, String technique) {
        // 起手必须成功，避免后面的窗口断言掩盖绑定或费用错误。
        var outcome = TechniqueFabric.service().cast(mob.getUUID(), request(mob, target, technique));
        context.assertTrue(outcome.getFailure() == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED
                && outcome.hasBinding(), "行矢起手失败：" + outcome);
    }

    private static CastRequest request(Pillager mob, LivingEntity target, String technique) {
        // 精确版本来自正式已学目录，目标由可信生物宿主提供。
        return CastRequest.newBuilder()
                .setBodyId(body(mob).id().value())
                .setTechnique(reference(mob, technique))
                .setTargetId(target.getStringUUID())
                .setCastId(UUID.randomUUID().toString())
                .build();
    }

    private static void assertChosen(GameTestHelper context, Pillager mob, LivingEntity target,
                                     Set<String> expected) {
        // 用正式选择器的最高分查看处境候选，不改熟练度或随机权重。
        var selected = HumanoidBrain.topStrike(mob, target, body(mob));
        context.assertTrue(selected.isPresent()
                        && expected.stream().anyMatch(id -> selected.orElseThrow().getId().equals(PREFIX + id)),
                "行矢处境选择错误：" + selected + "，预期 " + expected);
    }
}
