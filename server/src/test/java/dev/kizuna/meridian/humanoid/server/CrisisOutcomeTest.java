package dev.kizuna.meridian.humanoid.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.CrisisRecord;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Feud;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Grudge;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Pending;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.Test;

/** 一场怎么了结：三种结局改记录、逃的判定、赢了掉哪卷、记仇的人回来什么样、稀有卷怎么认。 */
public class CrisisOutcomeTest {
    private static final CrisisRules RULES = CrisisRules.defaults();
    private static final long DAY = CrisisRules.DAY_TICKS;
    private static final long NOW = 40 * DAY;
    private static final double HEAT = 80;
    private static final String FEUD = CrisisFeuds.key("test:orphan", 0);
    private static final String PILLAGER = "minecraft:pillager";
    private static final String LEADER = UUID.fromString("00000000-0000-0000-0000-0000000000aa").toString();
    private static final List<CrisisFeuds.Entry> ENTRIES = List.of(new CrisisFeuds.Entry(FEUD,
            new CrisisThreat("test:feud", new CrisisThreat.DayRange(8, 14), new CrisisThreat.DayRange(12, 20), 2,
                    HumanoidTier.WANDERING_QI, Optional.of("test:foe"))));
    private static final ByteString SCROLL = ByteString.copyFromUtf8("scroll");

    @Test
    public void winKeepsLittleHeatAdvancesTheFeudAndEndsTheGrudge() {
        // 赢：风声留两成，了结数 +1，排着的清掉，仇家拨次 +1 并在 12–20 天里定下一拨，记仇的人了断。
        CrisisRecord won = CrisisOutcome.settle(arrived(0, false).toBuilder().setGrudge(grudge(1)).build(),
                CrisisOutcome.Settlement.win(), ENTRIES, RULES, 7);
        assertEquals(HEAT * RULES.heat().keptAfterWin(), won.getHeat(), 1e-9);
        assertEquals(1, won.getSettledCount());
        assertEquals(NOW, won.getSettledAt());
        assertFalse(won.hasPending());
        assertFalse(won.hasGrudge());
        Feud feud = won.getFeuds(0);
        assertEquals(1, feud.getStage());
        assertFalse(feud.getFinished());
        assertFalse(CrisisFeuds.ended(arrived(0, false), won, FEUD));
        assertTrue(feud.getDueAt() >= NOW + 12 * DAY && feud.getDueAt() <= NOW + 20 * DAY);
    }

    @Test
    public void roadOutcomesPreserveFullRaidState() {
        var full = arrived(0, false /* grudgeLeads */);
        var pending = full.getPending().toBuilder()
                .setRoadRequest(1)
                .clearFeud()
                .build();
        var before = full.toBuilder()
                .setGrudge(grudge(1))
                .setSettledAt(DAY)
                .setPending(pending)
                .build();
        var expected = before.toBuilder()
                .setRoadCount(1)
                .setRoadSettledAt(NOW)
                .clearPending()
                .build();
        var outcomes = List.of(CrisisOutcome.Settlement.win(), CrisisOutcome.Settlement.escape(),
                CrisisOutcome.Settlement.loss(SCROLL));
        for (var outcome : outcomes) {
            assertEquals(expected, CrisisOutcome.settle(before, outcome, ENTRIES, RULES, 0));
        }
    }

    @Test
    public void winningTheLastStageEndsTheFeud() {
        // 两拨的仇家打赢第 2 拨（下标 1）就了断；「了断」只认这一回从没打完变成打完，已经打完的再比不算。
        CrisisRecord won = CrisisOutcome.settle(arrived(1, false), CrisisOutcome.Settlement.win(), ENTRIES, RULES, 7);
        assertEquals(2, won.getFeuds(0).getStage());
        assertTrue(won.getFeuds(0).getFinished());
        assertTrue(CrisisFeuds.ended(arrived(1, false), won, FEUD));
        assertFalse(CrisisFeuds.ended(won, won, FEUD));
        assertFalse(CrisisFeuds.ended(arrived(1, false), won, "test:absent#0"));
    }

    @Test
    public void escapeTurnsTheLeaderIntoTheGrudgeHolder() {
        // 逃：风声留一半，拨次不动、下一拨隔最少的 12 天；头目成了记仇的人，物种、档位、卷和这一身照来人时记的。
        CrisisRecord escaped = CrisisOutcome.settle(arrived(0, false), CrisisOutcome.Settlement.escape(), ENTRIES,
                RULES, 7);
        assertEquals(HEAT * RULES.heat().keptAfterEscape(), escaped.getHeat(), 1e-9);
        assertEquals(0, escaped.getFeuds(0).getStage());
        assertEquals(NOW + 12 * DAY, escaped.getFeuds(0).getDueAt());
        Grudge grudge = escaped.getGrudge();
        assertEquals(PILLAGER, grudge.getSpecies());
        assertEquals("true_qi", grudge.getTier());
        assertEquals(List.of("test:upper"), grudge.getVolumesList());
        assertEquals("test:feud", grudge.getParty());
        assertEquals(FEUD, grudge.getFeud());
        assertEquals(1, grudge.getEncounters());
        assertEquals(LEADER, grudge.getEntity());
        assertTrue(grudge.getCarriedList().isEmpty());
    }

    @Test
    public void lossToTheGrudgeHolderAddsToWhatItCarries() {
        // 输给带队回来的记仇的人：交手次数 +1，原先夺走的留着，这次夺走的接在后面；队伍表名字不变。
        CrisisRecord record = arrived(0, true).toBuilder().setGrudge(grudge(2).toBuilder().addCarried(SCROLL))
                .build();
        ByteString potion = ByteString.copyFromUtf8("potion");
        Grudge grudge = CrisisOutcome.settle(record, CrisisOutcome.Settlement.loss(potion), ENTRIES, RULES, 7)
                .getGrudge();
        assertEquals(3, grudge.getEncounters());
        assertEquals(List.of(SCROLL, potion), grudge.getCarriedList());
        assertEquals("test:old", grudge.getParty());
        assertEquals(List.of("test:upper"), grudge.getVolumesList());
    }

    @Test
    public void escapeNeedsDistanceAndUnseenTimeOrOutlastingTheDay() {
        // 远了但刚被看见不算；远了又 30 秒没被看见算；近的不算；撑过一天不论远近都算。
        Pending pending = Pending.newBuilder().setArrived(true).setArrivedAt(NOW).setLastSeenAt(NOW).build();
        CrisisRules.Outcome outcome = RULES.outcome();
        long unseen = NOW + outcome.escapeUnseenSeconds() * 20L;
        assertFalse(CrisisOutcome.escaped(pending, unseen - 20, 100, outcome));
        assertTrue(CrisisOutcome.escaped(pending, unseen, 100, outcome));
        assertTrue(CrisisOutcome.escaped(pending, unseen, Double.POSITIVE_INFINITY, outcome));
        assertFalse(CrisisOutcome.escaped(pending, unseen, outcome.escapeDistance() - 1, outcome));
        Pending justSeen = pending.toBuilder().setLastSeenAt(NOW + DAY).build();
        assertTrue(CrisisOutcome.escaped(justSeen, NOW + DAY, 5, outcome));
        CrisisRecord record = CrisisRecord.newBuilder().setOnlineTicks(unseen).setPending(pending).build();
        assertEquals(unseen, CrisisOutcome.seen(record).getPending().getLastSeenAt());
    }

    @Test
    public void spoilsPreferTheFoeVolumeThenALeaderRareVolume() {
        // 有 foe_volume 掉它；没有就在头目会的稀有卷里挑，同一种子同一卷；头目不会稀有卷就不掉。
        Pending pending = Pending.newBuilder().addAllLeaderVolumes(List.of("test:upper", "test:lower", "test:rare"))
                .build();
        assertEquals(Optional.of("test:foe"), CrisisOutcome.spoils(pending, Optional.of("test:foe"),
                volume -> false, 7));
        Optional<String> picked = CrisisOutcome.spoils(pending, Optional.empty(), volume -> !volume.endsWith("upper"),
                7);
        assertTrue(picked.equals(Optional.of("test:lower")) || picked.equals(Optional.of("test:rare")));
        assertEquals(picked, CrisisOutcome.spoils(pending, Optional.empty(), volume -> !volume.endsWith("upper"), 7));
        assertEquals(Optional.empty(), CrisisOutcome.spoils(pending, Optional.empty(), volume -> false, 7));
    }

    @Test
    public void grudgeHolderComesBackStrongerEachTime() {
        // 游炁记仇的人碰上真炁这一档：档位升到真炁，多会表里第一卷还不会的；会齐了以后再回来就精锐出生。
        VolumeTable table = table();
        Grudge wandering = grudge(1).toBuilder().setTier("wandering_qi").addVolumes("test:upper").build();
        CrisisParty.Member lineupLeader = new CrisisParty.Member(PILLAGER, HumanoidTier.TRUE_QI, false);
        CrisisOutcome.Return back = CrisisOutcome.returning(wandering, lineupLeader, table);
        assertEquals(new CrisisParty.Member(PILLAGER, HumanoidTier.TRUE_QI, false), back.leader());
        assertEquals(List.of("test:upper", "test:lower"), back.volumes());
        Grudge learned = wandering.toBuilder().setTier("true_qi").clearVolumes().addAllVolumes(back.volumes()).build();
        CrisisParty.Member weaker = new CrisisParty.Member(PILLAGER, HumanoidTier.WANDERING_QI, false);
        CrisisOutcome.Return again = CrisisOutcome.returning(learned, weaker, table);
        assertEquals(new CrisisParty.Member(PILLAGER, HumanoidTier.TRUE_QI, true), again.leader());
        assertEquals(List.of("test:upper", "test:lower"), again.volumes());
    }

    @Test
    public void rareVolumesAreOnlyEverGrantedAtLowChance() {
        // 每一处授这卷的概率都不超过线才算稀有；有一处是常见的就不算；表里没有的不算。
        VolumeTable table = table();
        assertTrue(table.rare("test:lower", RULES.heat().rise().rareChance()));
        assertFalse(table.rare("test:upper", RULES.heat().rise().rareChance()));
        assertFalse(table.rare("test:absent", RULES.heat().rise().rareChance()));
        VolumeTable common = table.plus(new VolumeTable(Map.of("minecraft:vindicator",
                Map.of(HumanoidTier.TRUE_QI, List.of(new VolumeTable.Grant("test:lower", 1.0))))));
        assertFalse(common.rare("test:lower", RULES.heat().rise().rareChance()));
    }

    private static CrisisRecord arrived(int stage, boolean grudgeLeads) {
        // 第 40 天，风声 80，仇家第 stage 拨的头目（真炁掠夺者，会一卷）来了。
        Pending pending = Pending.newBuilder().setParty("test:feud").setFeud(FEUD).setStage(stage).setArrived(true)
                .setArrivedAt(NOW - 100).setLeader(LEADER).setLeaderSpecies(PILLAGER).setLeaderTier("true_qi")
                .addLeaderVolumes("test:upper").setGrudgeLeads(grudgeLeads).build();
        return CrisisRecord.newBuilder().setSchemaVersion(CrisisRecords.SCHEMA_VERSION).setOnlineTicks(NOW)
                .setHeat(HEAT).addFeuds(Feud.newBuilder().setKey(FEUD).setStarted(true).setStage(stage))
                .setPending(pending).build();
    }

    private static Grudge grudge(int encounters) {
        // 之前一张队伍表留下的记仇的人。
        return Grudge.newBuilder().setSpecies(PILLAGER).setTier("true_qi").setParty("test:old")
                .setEncounters(encounters).build();
    }

    private static VolumeTable table() {
        // 掠夺者真炁档：常见的上卷、稀有的下卷。
        Map<HumanoidTier, List<VolumeTable.Grant>> tiers = new EnumMap<>(HumanoidTier.class);
        tiers.put(HumanoidTier.TRUE_QI, List.of(new VolumeTable.Grant("test:upper", 1.0),
                new VolumeTable.Grant("test:lower", 0.1)));
        return new VolumeTable(Map.of(PILLAGER, tiers));
    }
}
