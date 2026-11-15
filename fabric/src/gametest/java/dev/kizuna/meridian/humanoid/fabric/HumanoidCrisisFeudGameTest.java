package dev.kizuna.meridian.humanoid.fabric;

import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.attached;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.body;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.check;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.engine;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.finish;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.run;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.waitUntil;

import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.CrisisRecord;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Feud;
import dev.kizuna.meridian.humanoid.server.CrisisParty;
import dev.kizuna.meridian.humanoid.server.CrisisRecords;
import dev.kizuna.meridian.humanoid.server.CrisisRules;
import dev.kizuna.meridian.humanoid.server.CrisisThreat;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.technique.fabric.TechniqueVolumeSources;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.VolumeSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 修为招敌第 2 步：出身里写的 crisis 就是仇家。测试出身 {@code meridian_humanoid_test:orphan} 写了两条：
 * 第一条游炁就开始、8 天后来第一拨；第二条要真炁、突破后 2 天来。
 */
public final class HumanoidCrisisFeudGameTest {
    private static final int TIMEOUT = 400;
    private static final int RELOG_GAP_TICKS = 3;
    private static final long DAY = CrisisRules.DAY_TICKS;
    private static final String ORIGIN = "meridian_humanoid_test:orphan";
    private static final String FIRST = ORIGIN + "#0";
    private static final String SECOND = ORIGIN + "#1";
    private static final String PARTY = "meridian_humanoid_test:feud";
    private static final String FOE_VOLUME = "meridian_door_breaker:lower";

    @GameTest(maxTicks = TIMEOUT)
    public void chosenOriginStartsItsFeuds(GameTestHelper context) {
        // 选定出身：两条仇家马上写进身体，游炁那条开始计日、8 天后来；要真炁的那条等突破以后才开始，2 天后来。
        CrisisTestPlayer fixture = CrisisTestPlayer.create(context);
        ServerPlayer player = fixture.player();
        waitUntil(context, () -> attached(player), () -> {
            long chosenAt = engine().record(player).orElseThrow().getOnlineTicks();
            check(context, choose(context, player) > 0, "出身调试指令没有成功");
            CrisisRecord stored = CrisisRecords.read(body(player));
            check(context, feudKeys(stored).equals(List.of(FIRST, SECOND)), "身体里的仇家：" + feudKeys(stored));
            Feud first = stored.getFeuds(0);
            check(context, first.getStarted() && first.getDueAt() == chosenAt + 8 * DAY, "第一条：" + first);
            check(context, !stored.getFeuds(1).getStarted(), "游炁时要真炁的那条就开始了");
            check(context, run(context, "meridian body realm " + player.getScoreboardName() + " true_qi") > 0,
                    "境界指令没有成功");
            waitUntil(context, () -> feud(player, 1).getStarted(), () -> {
                long left = feud(player, 1).getDueAt() - engine().record(player).orElseThrow().getOnlineTicks();
                check(context, left > 2 * DAY - CrisisEngine.STEP_TICKS && left <= 2 * DAY, "突破后离第二条还有 " + left);
                finish(context, fixture);
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void oldPlayerIsReconciledOnRejoin(GameTestHelper context) {
        // 身体里有出身、招敌记录里却没有仇家的老玩家（比如装引擎之前就选了出身），重进时由进服事件补上。
        CrisisTestPlayer first = CrisisTestPlayer.create(context);
        ServerPlayer player = first.player();
        waitUntil(context, () -> attached(player), () -> {
            check(context, choose(context, player) > 0, "出身调试指令没有成功");
            engine().update(player, record -> record.toBuilder().clearFeuds().build());
            first.close();
            context.runAfterDelay(RELOG_GAP_TICKS, () -> {
                CrisisTestPlayer second = first.rejoin(context);
                waitUntil(context, () -> engine().record(second.player()).map(CrisisRecord::getFeudsCount)
                        .orElse(0) == 2, () -> {
                    check(context, feud(second.player(), 0).getStarted(), "补上的游炁那条没开始计日");
                    finish(context, second);
                });
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void dueFeudIsScheduledWithItsParty(GameTestHelper context) {
        // 离第一拨还有征兆期那么久时排上：队伍表是出身写的那张，属于第一条仇家，第 0 拨，到期日来人。
        CrisisTestPlayer fixture = CrisisTestPlayer.create(context);
        ServerPlayer player = fixture.player();
        waitUntil(context, () -> attached(player), () -> {
            check(context, choose(context, player) > 0, "出身调试指令没有成功");
            long dueAt = feud(player, 0).getDueAt();
            long omen = engine().rules().omen().days() * DAY;
            // 引擎每一步先记在线时间再看排不排，所以拨到差一步的时刻，下一步正好是征兆期的开头。
            engine().update(player, record -> record.toBuilder()
                    .setOnlineTicks(dueAt - omen - CrisisEngine.STEP_TICKS).build());
            waitUntil(context, () -> CrisisRecords.read(body(player)).hasPending(), () -> {
                var pending = CrisisRecords.read(body(player)).getPending();
                check(context, pending.getParty().equals(PARTY) && pending.getFeud().equals(FIRST)
                        && pending.getStage() == 0 && pending.getStrikeAt() == dueAt, "排上的一场：" + pending);
                finish(context, fixture);
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void startupCheckRejectsMissingReferences(GameTestHelper context) {
        // 开服第一个 tick 用的那一处核对：测试数据都对所以服务器起来了；指向不存在的队伍表或没装的卷都抛出。
        var parties = CrisisFabric.parties();
        var origins = CrisisOrigins.byOrigin();
        check(context, parties.containsKey("meridian_humanoid:covetous") && parties.containsKey(PARTY),
                "队伍表：" + parties.keySet());
        check(context, origins.getOrDefault(ORIGIN, List.of()).size() == 2, "出身的仇家：" + origins);
        CrisisFabric.requireReferences(parties, origins);
        CrisisThreat.DayRange days = new CrisisThreat.DayRange(1, 1);
        rejects(context, "队伍表不存在", parties, Map.of("meridian_humanoid_test:broken", List.of(new CrisisThreat(
                "meridian_humanoid_test:missing", days, days, 1, HumanoidTier.WANDERING_QI, Optional.empty()))));
        rejects(context, "foe_volume 没有装", parties, Map.of("meridian_humanoid_test:broken", List.of(new CrisisThreat(
                PARTY, days, days, 1, HumanoidTier.WANDERING_QI, Optional.of("meridian_humanoid_test:nothing")))));
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void foeVolumesBecomeCodexSources(GameTestHelper context) {
        // 功法谱的仇家来源：测试出身第一条仇家写了 foe_volume，登记出去正好一条，带出身显示名的语言键；
        // 第二条没写不登记。同一出身两条写同一卷只登记一次。开服和玩家进服时功法库会核对这些来源，写错了起不来。
        check(context, registered(), "招敌没把仇家来源登记进功法谱");
        var sources = CrisisVolumeSources.sources(CrisisOrigins.byOrigin()).stream()
                .filter(source -> source.getFoe().getOriginKey().equals("origin.meridian_humanoid_test.orphan.name"))
                .map(VolumeSource::getVolumeId)
                .toList();
        check(context, sources.equals(List.of(FOE_VOLUME)), "测试出身登记的仇家来源：" + sources);
        CrisisThreat.DayRange days = new CrisisThreat.DayRange(1, 1);
        var twice = new CrisisThreat(PARTY, days, days, 1, HumanoidTier.WANDERING_QI, Optional.of(FOE_VOLUME));
        var deduped = CrisisVolumeSources.sources(Map.of("meridian_humanoid_test:twice", List.of(twice, twice)));
        check(context, deduped.size() == 1
                && deduped.getFirst().getFoe().getOriginKey().equals("origin.meridian_humanoid_test.twice.name"),
                "同一出身同一卷登记了：" + deduped);
        context.succeed();
    }

    private static boolean registered() {
        // 功法库同一身份只收一次登记：再登记被拒，说明开服前已经登记过（没登记上时留下的空来源不碍别的测试）。
        try {
            TechniqueVolumeSources.register(CrisisVolumeSources.ID, server -> List.of());
            return false;
        } catch (IllegalArgumentException taken) {
            return taken.getMessage().contains(CrisisVolumeSources.ID.toString());
        }
    }

    private static int choose(GameTestHelper context, ServerPlayer player) {
        // 出身附属的调试指令直接定出身，同样触发选定事件。
        return run(context, "meridian debug origin set " + player.getScoreboardName() + " " + ORIGIN);
    }

    private static void rejects(GameTestHelper context, String expected,
                                Map<String, CrisisParty> parties,
                                Map<String, List<CrisisThreat>> origins) {
        // 核对要抛出，错误里说清楚是哪一处。
        try {
            CrisisFabric.requireReferences(parties, origins);
        } catch (IllegalStateException failure) {
            check(context, failure.getMessage().contains(expected), "核对的错误：" + failure.getMessage());
            return;
        }
        context.fail(Component.literal("核对没有拒绝：" + origins));
    }

    private static Feud feud(ServerPlayer player, int index) {
        // 内存里的第几条仇家。
        return engine().record(player).orElseThrow().getFeuds(index);
    }

    private static List<String> feudKeys(CrisisRecord record) {
        // 仇家的键，按记录里的顺序。
        return record.getFeudsList().stream().map(Feud::getKey).toList();
    }
}
