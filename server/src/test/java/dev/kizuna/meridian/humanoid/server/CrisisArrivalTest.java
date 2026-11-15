package dev.kizuna.meridian.humanoid.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.CrisisRecord;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Pending;
import dev.kizuna.meridian.humanoid.server.CrisisHeat.Band;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.Test;

/** 来人现身的规矩：时辰、按档和拨次选阵容、随从个数、仇家的卷、来了以后的记录。 */
public class CrisisArrivalTest {
    private static final String PILLAGER = "minecraft:pillager";
    private static final String VINDICATOR = "minecraft:vindicator";
    private static final int SAMPLES = 200;

    @Test
    public void onlyDuskAndNightFit() {
        // 日落起到天亮前；跨天按一天取余。
        assertFalse(CrisisArrival.nightfall(11_999));
        assertTrue(CrisisArrival.nightfall(12_000));
        assertTrue(CrisisArrival.nightfall(22_999));
        assertFalse(CrisisArrival.nightfall(23_000));
        assertTrue(CrisisArrival.nightfall(3 * CrisisRules.DAY_TICKS + 18_000));
        assertFalse(CrisisArrival.nightfall(3 * CrisisRules.DAY_TICKS + 6_000));
    }

    @Test
    public void lineupFollowsBandStageAndWeight() {
        // 同档取不超过当前拨次的最大拨次；同拨次两种按权重都抽得到（相邻种子也抽得开）；别的档不混进来。
        CrisisParty party = new CrisisParty(List.of(lineup(Band.WANDERING, 0, 1, PILLAGER),
                lineup(Band.TRUE_FEW, 0, 1, PILLAGER), lineup(Band.TRUE_MANY, 0, 1, PILLAGER),
                lineup(Band.TRUE_FEW, 2, 1, PILLAGER), lineup(Band.TRUE_FEW, 2, 3, VINDICATOR)));
        assertEquals(0, party.lineup(Band.TRUE_FEW, 1, CrisisSchedule.random(1)).stage());
        Map<String, Integer> picked = new HashMap<>();
        for (int seed = 0; seed < SAMPLES; seed++) {
            CrisisParty.Lineup lineup = party.lineup(Band.TRUE_FEW, 5, CrisisSchedule.random(seed));
            assertEquals(2, lineup.stage());
            picked.merge(lineup.leader().species(), 1, Integer::sum);
        }
        assertTrue(picked.toString(), picked.get(VINDICATOR) > picked.get(PILLAGER));
        assertEquals(Band.WANDERING, party.lineup(Band.WANDERING, 5, CrisisSchedule.random(1)).band());
    }

    @Test
    public void rosterPutsTheLeaderFirstAndCountsFollowersInRange() {
        // 头目在最前；随从个数落在最少到最多之间，两头都取得到。
        var followers = new CrisisParty.Followers(PILLAGER, HumanoidTier.WANDERING_QI, 1, 3);
        var leader = new CrisisParty.Member(VINDICATOR, HumanoidTier.TRUE_QI, true);
        var lineup = new CrisisParty.Lineup(Band.TRUE_MANY, 0, 1, leader, List.of(followers));
        Set<Integer> sizes = new HashSet<>();
        for (int seed = 0; seed < SAMPLES; seed++) {
            List<CrisisParty.Member> roster = lineup.roster(CrisisSchedule.random(seed));
            assertEquals(leader, roster.getFirst());
            assertTrue(roster.subList(1, roster.size()).stream().noneMatch(CrisisParty.Member::elite));
            sizes.add(roster.size());
        }
        assertEquals(Set.of(2, 3, 4), sizes);
    }

    @Test
    public void foeVolumeComesWithTheLastStageOfAFeud() {
        // 两拨的仇家第 2 拨才带；不限拨数的每拨都带；通用招敌和没写 foe_volume 的不带。
        var limited = new CrisisFeuds.Entry("test:orphan#0", threat(2, Optional.of("test:lower")));
        var endless = new CrisisFeuds.Entry("test:orphan#1", threat(0, Optional.of("test:upper")));
        var plain = new CrisisFeuds.Entry("test:orphan#2", threat(1, Optional.empty()));
        List<CrisisFeuds.Entry> entries = List.of(limited, endless, plain);
        assertEquals(Optional.empty(), CrisisArrival.foeVolume(pending("test:orphan#0", 0), entries));
        assertEquals(Optional.of("test:lower"), CrisisArrival.foeVolume(pending("test:orphan#0", 1), entries));
        assertEquals(Optional.of("test:upper"), CrisisArrival.foeVolume(pending("test:orphan#1", 0), entries));
        assertEquals(Optional.empty(), CrisisArrival.foeVolume(pending("test:orphan#2", 0), entries));
        assertEquals(Optional.empty(), CrisisArrival.foeVolume(pending("", 0), entries));
    }

    @Test
    public void arrivalIsRecordedWithLeaderAndRaiders() {
        // 来了：记时刻、头目（物种、档位、会的卷、是不是记仇的人）、全部来人，逃脱从这一刻算；调试立刻来人把来人时刻提到现在。
        CrisisRecord record = CrisisRecord.newBuilder().setOnlineTicks(500)
                .setPending(pending("", 0).toBuilder().setStrikeAt(900)).build();
        assertEquals(500, CrisisArrival.strikeNow(record).getPending().getStrikeAt());
        UUID leader = UUID.randomUUID();
        UUID follower = UUID.randomUUID();
        var shown = new CrisisArrival.Leader(leader, "minecraft:evoker", HumanoidTier.TRUE_QI,
                List.of("test:a", "test:b"), true);
        Pending arrived = CrisisArrival.arrived(record, shown, List.of(leader, follower)).getPending();
        assertTrue(arrived.getArrived());
        assertEquals("minecraft:evoker", arrived.getLeaderSpecies());
        assertEquals("true_qi", arrived.getLeaderTier());
        assertEquals(List.of("test:a", "test:b"), arrived.getLeaderVolumesList());
        assertTrue(arrived.getGrudgeLeads());
        assertEquals(500, arrived.getArrivedAt());
        assertEquals(500, arrived.getLastSeenAt());
        assertEquals(leader.toString(), arrived.getLeader());
        assertEquals(List.of(leader.toString(), follower.toString()), arrived.getRaidersList());
    }

    @Test
    public void listedVolumesIgnoreChanceAndKeepTableOrder() {
        // 精锐头目全会的那几卷：这一档写着的每一卷，按表里的顺序，概率再低也在。
        Map<HumanoidTier, List<VolumeTable.Grant>> tiers = new EnumMap<>(HumanoidTier.class);
        tiers.put(HumanoidTier.TRUE_QI, List.of(new VolumeTable.Grant("test:upper", 1.0),
                new VolumeTable.Grant("test:lower", 0.1)));
        VolumeTable table = new VolumeTable(Map.of(PILLAGER, tiers));
        assertEquals(List.of("test:upper", "test:lower"), table.listed(PILLAGER, HumanoidTier.TRUE_QI));
        assertEquals(List.of(), table.listed(PILLAGER, HumanoidTier.WANDERING_QI));
        assertEquals(List.of(), table.listed(VINDICATOR, HumanoidTier.TRUE_QI));
    }

    private static CrisisParty.Lineup lineup(Band band, int stage, int weight, String leader) {
        // 只有头目的阵容，用头目的物种分辨抽到哪一种。
        return new CrisisParty.Lineup(band, stage, weight,
                new CrisisParty.Member(leader, HumanoidTier.TRUE_QI, false), List.of());
    }

    private static CrisisThreat threat(int stages, Optional<String> foeVolume) {
        // 计日参数不影响带不带卷。
        CrisisThreat.DayRange days = new CrisisThreat.DayRange(1, 2);
        return new CrisisThreat("test:feud", days, days, stages, HumanoidTier.WANDERING_QI, foeVolume);
    }

    @Test
    public void strikeNowPullsUnreachedOmensUpToTheStrike() {
        // 排着两次征兆、只给了一次就叫人来：没到点的那次压到来人那一刻，给过的不动，记录仍然有效。
        CrisisRecord record = CrisisRecords.empty().toBuilder().setOnlineTicks(500)
                .setPending(pending("", 0).toBuilder().setScheduledAt(100).addOmenAt(300).addOmenAt(700)
                        .setOmensGiven(1).setStrikeAt(900)).build();
        CrisisRecord struck = CrisisArrival.strikeNow(record);
        assertEquals(500, struck.getPending().getStrikeAt());
        assertEquals(List.of(300L, 500L), struck.getPending().getOmenAtList());
        assertEquals(1, struck.getPending().getOmensGiven());
        CrisisRecords.validate(struck);
        CrisisRecord late = record.toBuilder().setOnlineTicks(1000).build();
        assertEquals(late.getPending(), CrisisArrival.strikeNow(late).getPending());
    }

    private static Pending pending(String feud, int stage) {
        // 排着的一场，只填带卷要看的两项。
        return Pending.newBuilder().setParty("test:feud").setFeud(feud).setStage(stage).build();
    }
}
