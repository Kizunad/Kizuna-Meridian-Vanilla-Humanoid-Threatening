package dev.kizuna.meridian.humanoid.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.CrisisRecord;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Feud;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

/** 出身的仇家：参数形状、补上记录、到了境界才计日、按写的先后排、和通用招敌共用间隔。 */
public class CrisisFeudsTest {
    private static final CrisisRules RULES = CrisisRules.defaults();
    private static final long DAY = CrisisRules.DAY_TICKS;
    private static final CrisisThreat ORPHAN = threat("test:feud", 8, 14, HumanoidTier.WANDERING_QI);
    private static final CrisisThreat LATE = threat("test:late", 2, 2, HumanoidTier.TRUE_QI);

    @Test
    public void threatParamsAreCheckedForShapeAndRange() {
        // 队伍表要带命名空间；天数区间不能倒着写、不能为负；拨次不能为负；凡人不能当 min_realm。
        CrisisThreat.DayRange days = new CrisisThreat.DayRange(1, 2);
        assertThrows(IllegalArgumentException.class, () -> new CrisisThreat.DayRange(3, 2));
        assertThrows(IllegalArgumentException.class, () -> new CrisisThreat.DayRange(-1, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new CrisisThreat("feud", days, days, 1, HumanoidTier.WANDERING_QI, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new CrisisThreat("test:feud", days, days, -1, HumanoidTier.WANDERING_QI, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new CrisisThreat("test:feud", days, days, 1, HumanoidTier.MORTAL, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new CrisisThreat("test:feud", days, days, 1, HumanoidTier.WANDERING_QI, Optional.of("lower")));
    }

    @Test
    public void reconcileAddsMissingFeudsOnce() {
        // 选定或进服时补上记录里还没有的仇家；再补一次不重复，已有的拨次不动。
        List<CrisisFeuds.Entry> entries = entries();
        CrisisRecord record = CrisisFeuds.reconcile(CrisisRecords.empty(), entries);
        assertEquals(List.of("test:orphan#0", "test:orphan#1"),
                record.getFeudsList().stream().map(Feud::getKey).toList());
        record = record.toBuilder().setFeuds(0, record.getFeuds(0).toBuilder().setStage(2)).build();
        assertSame(record, CrisisFeuds.reconcile(record, entries));
        assertEquals(2, record.getFeuds(0).getStage());
    }

    @Test
    public void countingStartsWhenTheRealmIsReached() {
        // 游炁那条一补上就开始数，第一拨落在 8～14 天；要真炁的那条等突破以后才开始，同一种子同一天。
        CrisisRecord record = at(CrisisFeuds.reconcile(CrisisRecords.empty(), entries()), DAY);
        record = CrisisFeuds.start(record, entries(), 42);
        Feud first = record.getFeuds(0);
        assertTrue(first.getStarted());
        assertTrue(first.getDueAt() >= 9 * DAY && first.getDueAt() <= 15 * DAY && first.getDueAt() % DAY == 0);
        assertFalse(record.getFeuds(1).getStarted());
        assertEquals(first, CrisisFeuds.start(at(record, 3 * DAY), entries(), 42).getFeuds(0));
        record = CrisisFeuds.start(at(record, 3 * DAY).toBuilder().setTrueQi(true).build(), entries(), 42);
        assertEquals(5 * DAY, record.getFeuds(1).getDueAt());
    }

    @Test
    public void dueFeudsGoInWrittenOrderAndShareTheInterval() {
        // 两条同时到了取先写的那条；征兆期提前开始，来人的时刻就是到期日；排着一场或间隔没过都不排。
        CrisisRecord record = started(10 * DAY, 10 * DAY);
        assertTrue(CrisisFeuds.due(at(record, 6 * DAY), entries(), RULES).isEmpty());
        CrisisSchedule.Raid raid = CrisisFeuds.due(at(record, 7 * DAY), entries(), RULES).orElseThrow();
        assertEquals("test:orphan#0", raid.feud());
        assertEquals("test:feud", raid.party());
        assertEquals(10 * DAY, raid.strikeAt());
        CrisisRecord pending = CrisisSchedule.schedule(at(record, 7 * DAY), raid, 2);
        assertTrue(CrisisFeuds.due(pending, entries(), RULES).isEmpty());
        CrisisRecord recent = at(record, 12 * DAY).toBuilder().setSettledCount(1).setSettledAt(5 * DAY).build();
        assertTrue(CrisisFeuds.due(recent, entries(), RULES).isEmpty());
        assertEquals(18 * DAY, CrisisFeuds.due(at(recent, 15 * DAY), entries(), RULES).orElseThrow().strikeAt());
    }

    @Test
    public void finishedOrUnknownFeudsAreNotScheduled() {
        // 了断的不再排；记录里有、出身里已经没有的那条也不排。
        CrisisRecord record = started(6 * DAY, 6 * DAY);
        record = record.toBuilder().setFeuds(0, record.getFeuds(0).toBuilder().setFinished(true)).build();
        CrisisSchedule.Raid raid = CrisisFeuds.due(at(record, 6 * DAY), entries(), RULES).orElseThrow();
        assertEquals("test:orphan#1", raid.feud());
        assertTrue(CrisisFeuds.due(at(record, 6 * DAY), entries().subList(0, 1), RULES).isEmpty());
    }

    private static CrisisRecord started(long firstDue, long secondDue) {
        // 两条都已开始计日的记录。
        CrisisRecord record = CrisisFeuds.reconcile(CrisisRecords.empty(), entries());
        return record.toBuilder()
                .setFeuds(0, record.getFeuds(0).toBuilder().setStarted(true).setDueAt(firstDue))
                .setFeuds(1, record.getFeuds(1).toBuilder().setStarted(true).setDueAt(secondDue))
                .build();
    }

    private static List<CrisisFeuds.Entry> entries() {
        // 一个出身写了两条：先游炁就开始的，再要真炁的。
        return List.of(new CrisisFeuds.Entry(CrisisFeuds.key("test:orphan", 0), ORPHAN),
                new CrisisFeuds.Entry(CrisisFeuds.key("test:orphan", 1), LATE));
    }

    private static CrisisThreat threat(String party, int firstMin, int firstMax, HumanoidTier minRealm) {
        // 间隔、拨次不影响计日，取定值。
        return new CrisisThreat(party, new CrisisThreat.DayRange(firstMin, firstMax),
                new CrisisThreat.DayRange(12, 20), 3, minRealm, Optional.empty());
    }

    private static CrisisRecord at(CrisisRecord record, long onlineTicks) {
        // 把在线时间拨到某一刻。
        return record.toBuilder().setOnlineTicks(onlineTicks).build();
    }
}
