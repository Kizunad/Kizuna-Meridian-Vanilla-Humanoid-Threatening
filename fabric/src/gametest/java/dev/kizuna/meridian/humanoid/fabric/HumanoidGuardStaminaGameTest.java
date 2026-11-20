package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.BodyActions;
import dev.kizuna.meridian.human.fabric.HumanGuardStamina;
import dev.kizuna.meridian.human.server.HumanStamina;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.HealthMessages.FatigueRelief;
import dev.kizuna.meridian.protocol.pb.HealthMessages.RestoreFatigue;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EndActiveRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.meridian.vanilla.VanillaDamageBridge.CommitResult;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

import static dev.kizuna.meridian.humanoid.fabric.HumanoidGameTestTicks.DEFAULT_TIMEOUT;

/** 七种生人通过正式功法服务起守，由同一实体的身体、命中回执和 NBT 验证付费。 */
public final class HumanoidGuardStaminaGameTest {
    private static final String GUARD = "meridian_basic_techniques:guard";
    private static final TechniqueReference REFERENCE = TechniqueReference.newBuilder()
            .setId(GUARD)
            .setVersion(1)
            .build();
    private static final long SECOND = 1000;
    private static final long WARMUP = 400;
    private static final long DELAY = 1750;
    private static final double HOLD_COST = 10000;
    // 一秒费用少半毫秒的余量，逐组织浮点分摊后仍落在同一个耗尽毫秒。
    private static final double ALMOST_ONE_SECOND = 9995;
    private static final double HIT_COST = 9000;
    private static final int MUSCLES = 18;
    private static final int OFFLINE_TICKS = 40;
    private static final int SAVE_TICK = 12;
    private static final int SPAWN_COORD = 2;
    private static final int DOSE = 10;
    private static final int GUARDED_DOSE = 7;
    private static final double EXACT_FATIGUE = 80;
    private static final double EPSILON = 1e-6;
    private static final List<String> FOREARMS = List.of("left_forearm", "right_forearm");

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void villagerPaysOnlyAfterWarmupOnLateInspection(GameTestHelper context) {
        var mob = raised(context, EntityTypes.VILLAGER);
        var body = body(mob);
        double full = available(body);
        long open = body.techniqueProgress().getActiveCast().getWarmupEndMillis();
        long start = body.currentBody().settledAt().elapsedMillis();
        context.assertTrue(open == start + WARMUP, "正式格挡的起手时长不符");
        settle(body, open - 1);
        close(context, available(body), full, "起手提前收费");
        context.assertTrue(BodyActions.usedTissues(body.currentBody()).isEmpty(), "起手提前占用肌肉");
        settle(body, open + SECOND);
        close(context, available(body), full - HOLD_COST, "迟到查询的第一秒维持费");
        settle(body, open + SECOND + SECOND);
        close(context, available(body), full - HOLD_COST - HOLD_COST, "守中自然回力或重复付款");
        checkHolding(context, mob);
        long revision = body.currentBody().revision();
        AuditReproSupport.inspect(mob);
        checkHolding(context, mob);
        context.assertTrue(body.currentBody().revision() == revision, "同一身体时刻重复收费");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void wanderingTraderReleasesWithoutBreaking(GameTestHelper context) {
        var mob = raised(context, EntityTypes.WANDERING_TRADER);
        var body = body(mob);
        var active = body.techniqueProgress().getActiveCast();
        long now = active.getWarmupEndMillis() + SECOND;
        settle(body, now);
        checkHolding(context, mob);
        double paid = available(body);
        var release = EndActiveRequest.newBuilder()
                .setBodyId(body.snapshot().getId())
                .setCastId(active.getRequest().getCastId())
                .build();
        var service = TechniqueFabric.service();
        var ended = service.endActive(mob.getUUID(), release);
        context.assertTrue(ended.getPhase() == CastPhase.RECOVERING, "松手没有收势 " + ended);
        context.assertTrue(service.activeGuard(mob.getUUID()).isEmpty(), "松手仍有守窗");
        context.assertTrue(BodyActions.usedTissues(body.currentBody()).isEmpty(), "松手未撤占用");
        settle(body, now + SECOND);
        context.assertTrue(available(body) > paid, "松手后没有恢复");
        context.assertTrue(!body.techniqueProgress().containsGuardBreakEnds(GUARD), "普通松手留下禁守");
        context.assertTrue(cast(mob).getPhase() == CastPhase.WARMUP, "普通松手后无法再起");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void pillagerPaysForGuardedDamageInHitReceipt(GameTestHelper context) {
        var mob = raised(context, EntityTypes.PILLAGER);
        var body = body(mob);
        settle(body, body.techniqueProgress().getActiveCast().getWarmupEndMillis());
        var hit = frontHit(context, mob);
        checkLoss(context, hit, FOREARMS, GUARDED_DOSE);
        var before = hit.before();
        var after = hit.body();
        var fatigueOnly = before.toBuilder();
        for (int i = 0; i < after.getTissueStatesCount(); i++) {
            fatigueOnly.getTissueStatesBuilder(i).setFatigue(after.getTissueStates(i).getFatigue());
        }
        close(context, joules(before) - joules(fatigueOnly.build()), HIT_COST, "命中回执的格挡费");
        context.assertTrue(after.getRevision() == before.getRevision() + 1, "命中费用与伤势分开提交");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void vindicatorBreaksOnLastPaidHit(GameTestHelper context) {
        var mob = raised(context, EntityTypes.VINDICATOR);
        var body = body(mob);
        settle(body, body.techniqueProgress().getActiveCast().getWarmupEndMillis());
        spend(body, available(body) - 1);
        var first = frontHit(context, mob);
        checkLoss(context, first, FOREARMS, GUARDED_DOSE);
        var paid = first.body();
        close(context, joules(paid), 0, "最后一击未付尽余量");
        var progress = TechniqueState.read(BodyProtobuf.fromMessage(paid));
        context.assertTrue(progress.getActiveCast().getSustained().getExhausted(), "耗尽当击未破防");
        context.assertTrue(TechniqueFabric.service().activeGuard(mob.getUUID()).isEmpty(), "耗尽当刻未撤窗");
        var second = frontHit(context, mob);
        checkLoss(context, second, List.of("head"), DOSE);
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void evokerMustWaitAndRecoverOneFifth(GameTestHelper context) {
        var mob = raised(context, EntityTypes.EVOKER);
        var body = body(mob);
        long open = body.techniqueProgress().getActiveCast().getWarmupEndMillis();
        settle(body, open);
        spend(body, available(body) - ALMOST_ONE_SECOND);
        long exhausted = open + SECOND;
        settle(body, exhausted + 1);
        long deadline = body.techniqueProgress().getGuardBreakEndsOrThrow(GUARD);
        context.assertTrue(deadline == exhausted + DELAY, "迟到扣费的禁守起点错误 " + (deadline - DELAY - open));
        settle(body, deadline - 1);
        context.assertTrue(cast(mob).getReason().equals(HumanGuardStamina.BROKEN), "截止前一毫秒缺破防拒绝");
        settle(body, deadline);
        context.assertTrue(cast(mob).getReason().equals(HumanGuardStamina.WEAK), "已到期但不足两成仍放行");
        restoreTo(body, EXACT_FATIGUE + EPSILON);
        context.assertTrue(cast(mob).getReason().equals(HumanGuardStamina.WEAK), "略低于两成仍放行");
        restoreTo(body, EXACT_FATIGUE);
        context.assertTrue(cast(mob).getPhase() == CastPhase.WARMUP, "恰到两成和时限未放行");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void witchHoldingSaveDoesNotPayOffline(GameTestHelper context) {
        var mob = raised(context, EntityTypes.WITCH);
        context.runAfterDelay(SAVE_TICK, () -> {
            var body = body(mob);
            checkHolding(context, mob);
            var saved = HumanoidEntityNbt.save(mob);
            double balance = available(body);
            mob.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            context.runAfterDelay(OFFLINE_TICKS, () -> {
                var restored = EntityTypes.WITCH.create(context.getLevel(), EntitySpawnReason.LOAD);
                HumanoidEntityNbt.load(restored, saved);
                context.assertTrue(context.getLevel().addFreshEntity(restored), "女巫重载失败");
                var snapshot = AuditReproSupport.inspect(restored).getBody();
                close(context, joules(snapshot), balance, "女巫离线仍扣费或回力");
                var resumed = body(restored);
                context.assertTrue(!resumed.techniqueProgress().hasActiveCast(), "健康查询恢复了旧守窗");
                context.assertTrue(BodyActions.usedTissues(resumed.currentBody()).isEmpty(), "读档残留占用");
                context.succeed();
            });
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void illusionerKeepsRemainingBanAcrossEntityNbt(GameTestHelper context) {
        var mob = raised(context, EntityTypes.ILLUSIONER);
        context.runAfterDelay(SAVE_TICK, () -> {
            checkHolding(context, mob);
            var body = body(mob);
            spend(body, available(body));
            context.assertTrue(TechniqueFabric.service().activeGuard(mob.getUUID()).isEmpty(), "零余力未破防");
            var saved = HumanoidEntityNbt.save(mob);
            long remaining = body.techniqueProgress().getGuardBreakEndsOrThrow(GUARD)
                    - body.currentBody().settledAt().elapsedMillis();
            mob.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            context.runAfterDelay(OFFLINE_TICKS, () -> {
                var restored = EntityTypes.ILLUSIONER.create(context.getLevel(), EntitySpawnReason.LOAD);
                HumanoidEntityNbt.load(restored, saved);
                context.assertTrue(context.getLevel().addFreshEntity(restored), "幻术师重载失败");
                var snapshot = AuditReproSupport.inspect(restored).getBody();
                close(context, joules(snapshot), 0, "离线期间破防疲劳恢复");
                var resumed = body(restored);
                long deadline = resumed.techniqueProgress().getGuardBreakEndsOrThrow(GUARD);
                context.assertTrue(deadline - snapshot.getSettledAt().getElapsedMillis() == remaining,
                        "离线吞掉剩余禁守时间");
                settle(resumed, deadline - 1);
                restoreTo(resumed, EXACT_FATIGUE);
                context.assertTrue(cast(restored).getReason().equals(HumanGuardStamina.BROKEN),
                        "余力已够仍须等到截止");
                settle(resumed, deadline);
                context.assertTrue(cast(restored).getPhase() == CastPhase.WARMUP, "重载后截止无法再起");
                context.succeed();
            });
        });
    }

    private static Mob raised(GameTestHelper context, EntityType<? extends Mob> type) {
        var mob = type.create(context.getLevel(), EntitySpawnReason.COMMAND);
        var pos = context.absolutePos(new BlockPos(SPAWN_COORD, SPAWN_COORD, SPAWN_COORD));
        mob.snapTo(pos.getX(), pos.getY(), pos.getZ(), 0, 0);
        mob.setNoAi(true /* aiDisabled */);
        mob.setNoGravity(true /* noGravity */);
        HumanoidSpeciesBirths.birth(mob, HumanoidTier.MORTAL);
        mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        context.assertTrue(context.getLevel().addFreshEntity(mob), "无法放置生人");
        var outcome = cast(mob);
        context.assertTrue(outcome.getPhase() == CastPhase.WARMUP, "生人无法直接起格挡 " + outcome);
        return mob;
    }

    private static CastOutcome cast(Mob mob) {
        var request = CastRequest.newBuilder()
                .setTechnique(REFERENCE)
                .setBodyId(body(mob).snapshot().getId())
                .setCastId(UUID.randomUUID().toString())
                .build();
        return TechniqueFabric.service().cast(mob.getUUID(), request);
    }

    private static HumanoidBodyState body(Mob mob) {
        return ((HumanoidEntityAccess) mob).meridian$humanoidBody();
    }

    private static void checkHolding(GameTestHelper context, Mob mob) {
        context.assertTrue(TechniqueFabric.service().activeGuard(mob.getUUID()).isPresent(), "成架缺守窗");
        var tissues = BodyActions.usedTissues(body(mob).currentBody());
        context.assertTrue(tissues.size() == MUSCLES, "守中未占十八块肌肉");
    }

    private static void settle(HumanoidBodyState body, long millis) {
        var now = body.snapshot().getSettledAt().toBuilder().setElapsedMillis(millis).build();
        body.settle(now);
    }

    static void spend(HumanoidBodyState body, double cost) {
        var current = body.currentBody();
        var activity = BodyActivity.newBuilder()
                .addAllTissueFatigue(HumanStamina.from(current).cost(cost))
                .build();
        var command = BodyCommand.newBuilder()
                .setBodyId(current.id().value())
                .setExpectedRevision(current.revision())
                .setExecuteActivity(activity)
                .build();
        body.submitRecovery(UUID.randomUUID().toString(), command);
    }

    static void restoreTo(HumanoidBodyState body, double fatigue) {
        var snapshot = body.snapshot();
        var muscles = HumanStamina.from(body.currentBody()).workingMuscles();
        var relief = RestoreFatigue.newBuilder().setSourceId("meridian_humanoid_test:medicine");
        for (var tissue : snapshot.getTissueStatesList()) {
            if (muscles.contains(tissue.getId())) {
                var dose = FatigueRelief.newBuilder()
                        .setTissueId(tissue.getId())
                        .setAmount(tissue.getFatigue() - fatigue)
                        .build();
                relief.addDoses(dose);
            }
        }
        var command = BodyCommand.newBuilder()
                .setBodyId(snapshot.getId())
                .setExpectedRevision(snapshot.getRevision())
                .setRestoreFatigue(relief)
                .build();
        var request = RequestEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(UUID.randomUUID().toString())
                .setBodyCommand(command)
                .build();
        body.apply(request, snapshot.getSettledAt());
    }

    private static CommitResult frontHit(GameTestHelper context, Mob mob) {
        var attacker = EntityTypes.COW.create(context.getLevel(), EntitySpawnReason.COMMAND);
        mob.setYRot(0);
        attacker.setPos(mob.position().add(0, 0, SPAWN_COORD));
        var source = mob.damageSources().mobAttack(attacker);
        var damage = new VanillaDamageFabric.DirectDamage(mob, source, "head", DOSE);
        return VanillaDamageFabric.damageDirect(damage).orElseThrow();
    }

    private static void checkLoss(GameTestHelper context, CommitResult result, List<String> targets, double dose) {
        var losses = result.health().getDamageResolution().getLossesList().stream()
                .filter(loss -> loss.getTarget().hasPartId()).toList();
        context.assertTrue(losses.size() == 1, "命中部位损失 " + losses);
        var loss = losses.getFirst();
        context.assertTrue(targets.contains(loss.getTarget().getPartId()), "格挡承力部位错误 " + loss);
        close(context, loss.getHp(), dose, "承力剂量");
    }

    static double available(HumanoidBodyState body) {
        return HumanStamina.from(body.currentBody()).availableJoules();
    }

    private static double joules(BodySnapshot snapshot) {
        return HumanStamina.from(BodyProtobuf.fromMessage(snapshot)).availableJoules();
    }

    private static void close(GameTestHelper context, double actual, double expected, String label) {
        context.assertTrue(Math.abs(actual - expected) < EPSILON,
                label + "，预期 " + expected + "，实际 " + actual);
    }
}
