package dev.kizuna.meridian.humanoid.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.CrisisRecord;
import dev.kizuna.meridian.humanoid.server.CrisisHeat.Band;
import dev.kizuna.meridian.humanoid.server.CrisisHeat.Observation;
import org.junit.Test;

/** 风声：第一次读到身体不涨，突破和开脉过节点才涨，没事时按天落；过线、保护期、最短间隔一起定能不能排。 */
public class CrisisHeatTest {
    private static final CrisisRules RULES = CrisisRules.defaults();
    private static final long DAY = CrisisRules.DAY_TICKS;

    @Test
    public void firstObservationOnlyRecordsTheBody() {
        // 老玩家第一次装上引擎时已是真炁、开了十二条，也不算刚突破。
        CrisisRecord seen = CrisisHeat.observe(CrisisRecords.empty(), new Observation(true, 12), RULES.heat().rise());
        assertEquals(0, seen.getHeat(), 0);
        assertTrue(seen.getBodySeen() && seen.getTrueQi());
        assertEquals(12, seen.getOpenedMeridians());
    }

    @Test
    public void breakthroughAndNodesRaiseHeatOnce() {
        // 从游炁三条开到真炁八条：过了 4、8 两个节点加突破；掉回七条再开回八条不重复涨。
        CrisisRecord record = CrisisHeat.observe(CrisisRecords.empty(), new Observation(false, 3), RULES.heat().rise());
        record = CrisisHeat.observe(record, new Observation(true, 8), RULES.heat().rise());
        assertEquals(60 + 15 + 15, record.getHeat(), 0);
        record = CrisisHeat.observe(record, new Observation(true, 7), RULES.heat().rise());
        record = CrisisHeat.observe(record, new Observation(true, 8), RULES.heat().rise());
        assertEquals(90, record.getHeat(), 0);
        assertEquals(8, record.getOpenedMeridians());
    }

    @Test
    public void heatDecaysPerDayAfterTheQuietPeriod() {
        // 涨了以后两天不落；第三天起每天落 10，落到 0 为止；中途再涨就重新等两天。
        CrisisRecord record = CrisisHeat.raise(CrisisRecords.empty(), 35);
        record = CrisisHeat.advance(record, 2 * DAY + DAY - 1, RULES.heat().decay());
        assertEquals(35, record.getHeat(), 0);
        record = CrisisHeat.advance(record, 1, RULES.heat().decay());
        assertEquals(25, record.getHeat(), 0);
        record = CrisisHeat.raise(record, 5);
        record = CrisisHeat.advance(record, 2 * DAY, RULES.heat().decay());
        assertEquals(30, record.getHeat(), 0);
        record = CrisisHeat.advance(record, 10 * DAY, RULES.heat().decay());
        assertEquals(0, record.getHeat(), 0);
    }

    @Test
    public void raidNeedsHeatProtectionIntervalAndReadiness() {
        // 风声过线还不够：头 5 天不排；游炁要开齐八条；上一场了结后要隔满 10 天。
        CrisisRecord record = CrisisRecords.empty().toBuilder().setBodySeen(true).setTrueQi(true)
                .setOpenedMeridians(8).setHeat(60).setOnlineTicks(5 * DAY - 1).build();
        assertFalse(CrisisHeat.heatRaidDue(record, RULES));
        record = record.toBuilder().setOnlineTicks(5 * DAY).build();
        assertTrue(CrisisHeat.heatRaidDue(record, RULES));
        CrisisRecord wandering = record.toBuilder().setTrueQi(false).setOpenedMeridians(7).build();
        assertFalse(CrisisHeat.heatRaidDue(wandering, RULES));
        assertTrue(CrisisHeat.heatRaidDue(wandering.toBuilder().setOpenedMeridians(8).build(), RULES));
        CrisisRecord settled = record.toBuilder().setSettledCount(1).setSettledAt(record.getOnlineTicks()).build();
        assertFalse(CrisisHeat.heatRaidDue(settled, RULES));
        settled = settled.toBuilder().setOnlineTicks(settled.getSettledAt() + 10 * DAY).build();
        assertTrue(CrisisHeat.heatRaidDue(settled, RULES));
        assertFalse(CrisisHeat.heatRaidDue(record.toBuilder().setHeat(49.9).build(), RULES));
    }

    @Test
    public void bandsFollowRealmAndMeridians() {
        // 游炁一档；真炁开到十二条以前算少，以后算多。
        assertEquals(Band.WANDERING, CrisisHeat.band(new Observation(false, 8), RULES.strength()));
        assertEquals(Band.TRUE_FEW, CrisisHeat.band(new Observation(true, 11), RULES.strength()));
        assertEquals(Band.TRUE_MANY, CrisisHeat.band(new Observation(true, 12), RULES.strength()));
    }
}
