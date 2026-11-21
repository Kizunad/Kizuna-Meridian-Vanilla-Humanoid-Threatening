package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.BodyTimeAdvancement;
import dev.kizuna.meridian.core.TimeCursor;
import dev.kizuna.meridian.human.server.HumanAgingRules;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidVariant;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.server.TechniqueState;
import java.util.OptionalLong;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import static dev.kizuna.meridian.humanoid.fabric.HumanoidGameTestTicks.DEFAULT_TIMEOUT;

/** 类人身体包装上的缓存和轻量时钟：身体每提交一次，读到的都必须是新的那一份。 */
public final class HumanoidBodyCacheGameTest {
    private static final int POSITION = 2;
    private static final int WAIT_TICKS = 5;
    private static final long SIMULATION_GAME_DAYS = 2;
    private static final double SPARE_LIFESPAN_SECONDS = 1;
    private static final long GAME_DAY_MILLIS = 1_200_000;
    private static final int UNLOAD_GAP_TICKS = 20;
    private static final int ONLINE_AFTER_UNLOAD_TICKS = 10;
    private static final double SECONDS_PER_GAME_DAY = 86_400;
    private static final int TRANSFER_WAIT_TICKS = 10;
    private static final int TARGET_WAIT_TICKS = 10;
    private static final long ONE_MILLISECOND = 1;
    private static final double OVERWORLD_REMAINING_SECONDS = 7_200;
    private static final double NETHER_REMAINING_SECONDS = 14_400;
    private static final double AGE_EPSILON = 1e-6;
    private static final BodyMessages.QiInventory EMPTY_INVENTORY = BodyMessages.QiInventory.getDefaultInstance();

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void naturalAgeUsesDimensionRateAndSplitsAtTransfer(GameTestHelper context) {
        Mob mob = spawn(context);
        try {
            HumanoidBodyState body = ((HumanoidEntityAccess) mob).meridian$humanoidBody();
            BodyMessages.TimeCursor start = body.snapshot().getSettledAt();
            double initialAge = body.snapshot().getVitality().getAgeSeconds();
            body.selectDimension("minecraft:overworld", start);
            body.settle(at(start, GAME_DAY_MILLIS));
            double overworldAge = body.snapshot().getVitality().getAgeSeconds();
            double expectedOverworldAge = initialAge + SECONDS_PER_GAME_DAY
                    * HumanAgingRules.DEFAULT_OVERWORLD_MULTIPLIER;
            context.assertTrue(Math.abs(overworldAge - expectedOverworldAge) < AGE_EPSILON,
                    "主世界游戏日年龄倍率错误");

            body.selectDimension("minecraft:the_nether", at(start, GAME_DAY_MILLIS));
            body.settle(at(start, GAME_DAY_MILLIS * SIMULATION_GAME_DAYS));
            double expectedNetherAge = expectedOverworldAge + SECONDS_PER_GAME_DAY
                    * HumanAgingRules.DEFAULT_NETHER_MULTIPLIER;
            context.assertTrue(Math.abs(body.snapshot().getVitality().getAgeSeconds() - expectedNetherAge)
                            < AGE_EPSILON,
                    "跨维度没有按源段和目标段分别计算年龄");
        } finally {
            mob.discard();
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void unloadedIntervalFreezesNaturalAge(GameTestHelper context) {
        Mob source = spawn(context);
        context.runAfterDelay(TRANSFER_WAIT_TICKS, () -> {
            BodyMessages.BodySnapshot savedBody = AuditReproSupport.inspect(source).getBody();
            var saved = HumanoidEntityNbt.save(source);
            // 实体区块卸载事件先冻结年龄，实体对象随后脱离世界，再从 NBT 加载新对象。
            ServerEntityEvents.ENTITY_UNLOAD.invoker().onUnload(source, context.getLevel());
            source.setRemoved(net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK);
            context.runAfterDelay(UNLOAD_GAP_TICKS, () -> {
                var restoredEntity = source.getType().create(context.getLevel(), EntitySpawnReason.LOAD);
                context.assertTrue(restoredEntity instanceof Mob, "卸载后无法创建生人实体");
                Mob restored = (Mob) restoredEntity;
                try {
                    HumanoidEntityNbt.load(restored, saved);
                    context.assertTrue(context.getLevel().addFreshEntity(restored), "卸载后生人重载失败");
                    long loadedAt = ((HumanoidEntityAccess) restored).meridian$loadedAtMillis();
                    context.assertTrue(loadedAt != HumanoidEntityAccess.NO_LOAD_TIMESTAMP,
                            "实体载入事件没有记录恢复边界");
                    BodyMessages.BodySnapshot firstQuery = AuditReproSupport.inspect(restored).getBody();
                    double firstAgeDelta = firstQuery.getVitality().getAgeSeconds()
                            - savedBody.getVitality().getAgeSeconds();
                    context.assertTrue(Math.abs(firstAgeDelta) < AGE_EPSILON,
                            "实体卸载间隔被计入自然年龄：" + firstAgeDelta);
                    context.runAfterDelay(ONLINE_AFTER_UNLOAD_TICKS, () -> {
                        try {
                            BodyMessages.BodySnapshot resumed = AuditReproSupport.inspect(restored).getBody();
                            assertAgeSegment(context, "实体重载后", firstQuery, resumed,
                                    HumanAgingRules.DEFAULT_OVERWORLD_MULTIPLIER);
                            context.succeed();
                        } finally {
                            restored.discard();
                        }
                    });
                } catch (Throwable failure) {
                    restored.discard();
                    throw failure;
                }
            });
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void realDimensionTransferRestoresBodyBeforeFirstQuery(GameTestHelper context) {
        // 原版跨维传送先复制实体，再由目标实体第一次健康查询恢复同一身体。
        Mob source = spawn(context);
        BodyMessages.BodySnapshot initial = AuditReproSupport.inspect(source).getBody();
        context.runAfterDelay(TRANSFER_WAIT_TICKS, () -> {
            try {
                BodyMessages.BodySnapshot beforeTransfer = AuditReproSupport.inspect(source).getBody();
                assertAgeSegment(context, "主世界传送前", initial, beforeTransfer,
                        HumanAgingRules.DEFAULT_OVERWORLD_MULTIPLIER);
                var nether = source.level().getServer().getLevel(net.minecraft.world.level.Level.NETHER);
                var destination = BlockPos.containing(source.position());
                nether.getChunkAt(destination);
                var transferAt = HumanoidFabric.now();
                var transition = new TeleportTransition(nether, Vec3.atCenterOf(destination), Vec3.ZERO,
                        source.getYRot(), source.getXRot(), java.util.Set.of(),
                        TeleportTransition.PLACE_PORTAL_TICKET);
                var transferred = source.teleport(transition);
                context.assertTrue(transferred instanceof Mob, "生人没有实际跨维传送");
                Mob target = (Mob) transferred;
                context.assertTrue(target.level() == nether, "生人没有进入下界");
                context.runAfterDelay(TARGET_WAIT_TICKS, () -> {
                    try {
                        var targetResponse = AuditReproSupport.inspect(target);
                        context.assertTrue(targetResponse.hasBody(),
                                "跨维目标身体恢复失败：" + targetResponse);
                        BodyMessages.BodySnapshot afterTransfer = targetResponse.getBody();
                        assertTransferAge(context, beforeTransfer, transferAt, afterTransfer);
                        BodyMessages.BodySnapshot afterTargetWait = AuditReproSupport.inspect(target).getBody();
                        assertAgeSegment(context, "下界传送后", afterTransfer, afterTargetWait,
                                HumanAgingRules.DEFAULT_NETHER_MULTIPLIER);
                        context.succeed();
                    } finally {
                        target.discard();
                    }
                });
            } catch (Throwable failure) {
                source.discard();
                throw failure;
            }
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void lifespanBoundaryUsesDimensionAgeRate(GameTestHelper context) {
        Mob overworld = spawn(context);
        assertLifespanBoundary(context, overworld, "minecraft:overworld", OVERWORLD_REMAINING_SECONDS);
        overworld.discard();

        Mob nether = spawn(context);
        assertLifespanBoundary(context, nether, "minecraft:the_nether", NETHER_REMAINING_SECONDS);
        nether.discard();
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void snapshotFollowsEveryCommit(GameTestHelper context) {
        // 身体没变时协议消息复用同一份；补满真元提交新版本后，读到的必须是新身体。
        Mob mob = spawn(context);
        try {
            HumanoidBodyState body = ((HumanoidEntityAccess) mob).meridian$humanoidBody();
            var before = body.snapshot();
            context.assertTrue(before == body.snapshot(), "身体没变时协议消息没有复用");
            DebugQi fill = DebugQi.newBuilder().setMode(DebugQiMode.FILL_LUMENS).build();
            context.assertTrue(HumanoidHealthHost.debugQi(mob, fill) == FailureCode.FAILURE_CODE_UNSPECIFIED,
                    "补满真元被拒");
            var current = body.currentBody();
            context.assertTrue(current.revision() > before.getRevision(), "补满真元没有提交新版本");
            context.assertTrue(body.snapshot().equals(BodyProtobuf.toMessage(current)),
                    "协议消息没有跟上已提交的身体");
        } finally {
            mob.discard();
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void techniqueProgressFollowsEveryCommit(GameTestHelper context) {
        // 新学一式会提交新身体；缓存的功法进度必须跟着换，否则大脑会按旧进度判断在不在施放。
        Mob mob = spawn(context);
        try {
            HumanoidBodyState body = ((HumanoidEntityAccess) mob).meridian$humanoidBody();
            TechniqueProgress before = body.techniqueProgress();
            context.assertTrue(before == body.techniqueProgress(), "身体没变时功法进度没有复用");
            TechniqueDefinition unknown = unknownTechnique(before);
            TechniqueFabric.service().learn(mob.getUUID(), unknown);
            TechniqueProgress committed = TechniqueState.read(body.currentBody());
            context.assertTrue(committed.getKnownCount() == before.getKnownCount() + 1, "新学的式没有写进身体");
            context.assertTrue(body.techniqueProgress().equals(committed), "功法进度没有跟上已提交的身体");
        } finally {
            mob.discard();
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void lightClockMatchesSettledBody(GameTestHelper context) {
        // 过几个 tick 以后，轻量时钟报的数要等于随后真正结清得到的身体时钟，而且确实往前走了。
        Mob mob = spawn(context);
        HumanoidBodyState body = ((HumanoidEntityAccess) mob).meridian$humanoidBody();
        long born = body.currentBody().settledAt().elapsedMillis();
        context.runAfterDelay(WAIT_TICKS, () -> {
            try {
                var committed = body.currentBody();
                OptionalLong clock = HumanoidTechniqueHost.clockMillis(mob);
                context.assertTrue(clock.isPresent(), "已绑定的活体没有报出时钟");
                context.assertTrue(body.currentBody() == committed, "读时钟不应结清身体");
                long settled = HumanoidTechniqueHost.prepare(mob).body().currentBody().settledAt().elapsedMillis();
                context.assertTrue(settled > born, "等待期间身体时钟没有前进");
                context.assertTrue(clock.getAsLong() == settled,
                        "轻量时钟 " + clock.getAsLong() + " 与结清后的身体时钟 " + settled + " 不一致");
            } finally {
                mob.discard();
            }
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void lightClockIsWithheldOnceLifespanIsUsedUp(GameTestHelper context) {
        // 寿元已经用完、身体还没被结清判死：真正结清会停在寿元终点并判死，所以轻量时钟必须答不出，
        // 让调用方去走会结清的路径。同样换一具身体、寿元还剩一秒时照常报数。
        Mob mob = spawn(context);
        try {
            HumanoidBodyState spare = rebindWithLifespanLeft(mob, SPARE_LIFESPAN_SECONDS);
            context.assertTrue(!spare.currentBody().vitality().dead(), "寿元未尽的身体不该是死的");
            context.assertTrue(HumanoidTechniqueHost.clockMillis(mob).isPresent(), "寿元未尽的活体没有报出时钟");

            HumanoidBodyState exhausted = rebindWithLifespanLeft(mob, 0);
            context.assertTrue(!exhausted.currentBody().vitality().dead(), "同一毫秒内不该已经结清判死");
            context.assertTrue(HumanoidTechniqueHost.clockMillis(mob).isEmpty(), "寿元用完后仍报出轻量时钟");
        } finally {
            mob.discard();
        }
        context.succeed();
    }

    private static BodyMessages.TimeCursor at(BodyMessages.TimeCursor base, long offsetMillis) {
        long elapsedMillis = base.getElapsedMillis() + offsetMillis;
        return base.toBuilder()
                .setElapsedMillis(elapsedMillis)
                .build();
    }

    private static void assertAgeSegment(GameTestHelper context, String segment,
                                         BodyMessages.BodySnapshot start,
                                         BodyMessages.BodySnapshot end,
                                         double dimensionMultiplier) {
        long elapsedMillis = end.getSettledAt().getElapsedMillis() - start.getSettledAt().getElapsedMillis();
        double agingRate = HumanAgingRules.GAME_DAY_AGE_SECONDS_PER_SIMULATION_SECOND
                * dimensionMultiplier;
        double expectedAge = start.getVitality().getAgeSeconds() + elapsedMillis / 1000.0 * agingRate;
        context.assertTrue(Math.abs(end.getVitality().getAgeSeconds() - expectedAge) < AGE_EPSILON,
                segment + "年龄流速错误，预期 " + expectedAge
                        + "，实际 " + end.getVitality().getAgeSeconds());
    }

    private static void assertTransferAge(GameTestHelper context, BodyMessages.BodySnapshot source,
                                          BodyMessages.TimeCursor transferAt,
                                          BodyMessages.BodySnapshot target) {
        long sourceMillis = transferAt.getElapsedMillis() - source.getSettledAt().getElapsedMillis();
        long targetMillis = target.getSettledAt().getElapsedMillis() - transferAt.getElapsedMillis();
        double sourceRate = HumanAgingRules.GAME_DAY_AGE_SECONDS_PER_SIMULATION_SECOND
                * HumanAgingRules.DEFAULT_OVERWORLD_MULTIPLIER;
        double targetRate = HumanAgingRules.GAME_DAY_AGE_SECONDS_PER_SIMULATION_SECOND
                * HumanAgingRules.DEFAULT_NETHER_MULTIPLIER;
        double expectedAge = source.getVitality().getAgeSeconds()
                + sourceMillis / 1000.0 * sourceRate
                + targetMillis / 1000.0 * targetRate;
        double actualAge = target.getVitality().getAgeSeconds();
        context.assertTrue(Math.abs(actualAge - expectedAge) < AGE_EPSILON,
                "跨维交接年龄重复或遗漏，预期 " + expectedAge + "，实际 " + actualAge);
    }

    private static void assertLifespanBoundary(GameTestHelper context, Mob mob, String dimensionId,
                                               double remainingSeconds) {
        HumanoidBodyState body = rebindWithLifespanLeft(mob, remainingSeconds);
        BodyMessages.TimeCursor start = BodyProtobuf.toTime(body.currentBody().settledAt());
        body.selectDimension(dimensionId, start);
        double agingRate = HumanAgingRules.configured().agingRate(dimensionId);
        long boundary = BodyTimeAdvancement.nextLifespanBoundary(body.currentBody(), agingRate)
                .orElseThrow()
                .elapsedMillis();
        long elapsedMillis = boundary - start.getElapsedMillis();
        context.assertTrue(elapsedMillis == 1_000, "" + dimensionId + "寿尽边界不是一秒");
        TimeCursor beforeBoundary = BodyProtobuf.fromTime(at(start, elapsedMillis - ONE_MILLISECOND));
        var beforeBoundaryPlan = new BodyTimeAdvancement.Plan(beforeBoundary, agingRate,
                BodyTimeAdvancement.Mode.ACTIVE);
        var beforeBoundaryResult = BodyTimeAdvancement.apply(body.currentBody(), beforeBoundaryPlan);
        context.assertTrue(!beforeBoundaryResult.died(), dimensionId + "寿尽前一毫秒误判死亡");
        context.assertTrue(body.projectedClockMillis(at(start, elapsedMillis - ONE_MILLISECOND)).isPresent(),
                dimensionId + "边界前一毫秒未保持存活");
        body.settle(at(start, elapsedMillis));
        context.assertTrue(body.currentBody().vitality().dead(), dimensionId + "寿尽边界没有判死");
    }

    private static HumanoidBodyState rebindWithLifespanLeft(Mob mob, double secondsLeft) {
        // 把寿命上限压到只剩这么多秒，换成一具新读档的身体并重新发布会话；年龄和已耗寿元不动。
        var access = (HumanoidEntityAccess) mob;
        var living = access.meridian$humanoidBody().snapshot();
        var vitality = living.getVitality();
        double limit = vitality.getAgeSeconds() + vitality.getSpentLifespanSeconds() + secondsLeft;
        var shortenedVitality = vitality.toBuilder()
                .setLifespanLimitSeconds(limit)
                .build();
        var shortened = emptyQi(living).toBuilder()
                .setVitality(shortenedVitality)
                .build();
        HumanoidTechniqueHost.removed(mob);
        var saved = new HumanoidBodyState.SavedBody(mob.getUUID(), shortened.toByteArray(), new byte[0][],
                HumanoidFabric.region(mob).sourceId());
        HumanoidBodyState body = HumanoidBodyState.restore(mob.getUUID(), saved, HumanoidFabric.now(),
                HumanoidSpecies.VINDICATOR);
        access.meridian$humanoidBody(body);
        HumanoidTechniqueHost.prepare(mob);
        return body;
    }

    private static BodyMessages.BodySnapshot emptyQi(BodyMessages.BodySnapshot body) {
        var builder = body.toBuilder().clearTransits();
        for (int index = 0; index < body.getMeridianStatesCount(); index++) {
            var meridian = body.getMeridianStates(index).toBuilder()
                    .setLumenQi(EMPTY_INVENTORY)
                    .setWallQi(EMPTY_INVENTORY)
                    .build();
            builder.setMeridianStates(index, meridian);
        }
        for (int index = 0; index < body.getOrganStatesCount(); index++) {
            var organ = body.getOrganStates(index).toBuilder()
                    .setBuffer(EMPTY_INVENTORY)
                    .clearProcess()
                    .build();
            builder.setOrganStates(index, organ);
        }
        return builder.build();
    }

    private static Mob spawn(GameTestHelper context) {
        // 真实生成的真炁卫道士：出生即绑定身体、发布会话并学完默认卷。
        Vec3 position = Vec3.atBottomCenterOf(context.absolutePos(new BlockPos(POSITION, POSITION, POSITION)));
        var selection = new HumanoidSpawns.Selection(HumanoidSpecies.VINDICATOR, HumanoidVariant.TRUE_QI);
        Mob mob = HumanoidSpawns.spawn(context.getLevel(), selection, position, EntitySpawnReason.COMMAND);
        mob.setNoAi(true /* noAi */);
        return mob;
    }

    private static TechniqueDefinition unknownTechnique(TechniqueProgress progress) {
        // 目录里任取一式还没学过的；天生会的式学了也不留记录，不能拿来验「新学一式」。
        return TechniqueFabric.service().registry().definitions().stream()
                .filter(definition -> !definition.getInnate())
                .filter(definition -> progress.getKnownList().stream()
                        .noneMatch(known -> known.getTechnique().getId().equals(definition.getId())))
                .findFirst().orElseThrow();
    }
}
