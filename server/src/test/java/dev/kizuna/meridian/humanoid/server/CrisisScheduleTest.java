package dev.kizuna.meridian.humanoid.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.CrisisRecord;
import dev.kizuna.meridian.humanoid.server.CrisisSchedule.OmenStep;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.Test;

/** 排场：征兆均匀铺在征兆期里，一场只留一张字条，没碰上村民的传言到点跳过，调试指令不等时刻，到点才来人。 */
public class CrisisScheduleTest {
    private static final CrisisRules RULES = CrisisRules.defaults();
    private static final long DAY = CrisisRules.DAY_TICKS;
    private static final int RAIDS = 20;

    @Test
    public void omensSpreadAcrossTheOmenPeriod() {
        // 第 6 天排上、第 9 天来人，三次征兆在 6.75、7.5、8.25 天。
        CrisisRecord record = CrisisRecords.empty().toBuilder().setOnlineTicks(6 * DAY).build();
        record = CrisisSchedule.schedule(record, CrisisSchedule.heatRaid(record, RULES), 3);
        assertEquals("meridian_humanoid:covetous", record.getPending().getParty());
        assertEquals(9 * DAY, record.getPending().getStrikeAt());
        assertEquals(List.of(6 * DAY + DAY * 3 / 4, 7 * DAY + DAY / 2, 8 * DAY + DAY / 4),
                record.getPending().getOmenAtList());
    }

    @Test
    public void oneNotePerRaidAndRumorsNeedVillagers() {
        // 第一次身边没村民就留字条；第二次只能等村民，过了这一次的窗口就跳过；第三次碰上村民说传言。
        CrisisRecord record = CrisisSchedule.schedule(CrisisRecords.empty(),
                new CrisisSchedule.Raid("test:feud", "", 0, 4 * DAY), 3);
        assertEquals(OmenStep.WAIT, CrisisSchedule.omenStep(record, true));
        record = at(record, DAY);
        assertEquals(OmenStep.NOTE, CrisisSchedule.omenStep(record, false));
        record = CrisisSchedule.omenDone(record, OmenStep.NOTE);
        record = at(record, 2 * DAY);
        assertEquals(OmenStep.WAIT, CrisisSchedule.omenStep(record, false));
        record = at(record, 3 * DAY);
        assertEquals(OmenStep.SKIP, CrisisSchedule.omenStep(record, false));
        record = CrisisSchedule.omenDone(record, OmenStep.SKIP);
        assertEquals(OmenStep.RUMOR, CrisisSchedule.omenStep(record, true));
        record = CrisisSchedule.omenDone(record, OmenStep.RUMOR);
        assertEquals(OmenStep.WAIT, CrisisSchedule.omenStep(record, true));
        assertEquals(3, record.getPending().getOmensGiven());
        assertFalse(CrisisSchedule.strikeDue(record));
        assertTrue(CrisisSchedule.strikeDue(at(record, 4 * DAY)));
    }

    @Test
    public void lastOmenIsTheNoteIfNoneWasLeft() {
        // 前面都碰上村民说了传言，最后一次也要留字条。
        CrisisRecord record = CrisisSchedule.schedule(CrisisRecords.empty(),
                new CrisisSchedule.Raid("test:feud", "", 0, 3 * DAY), 2);
        record = CrisisSchedule.omenDone(at(record, DAY), CrisisSchedule.omenStep(at(record, DAY), true));
        assertEquals(OmenStep.NOTE, CrisisSchedule.omenStep(at(record, 2 * DAY), true));
    }

    @Test
    public void debugOmenGivesTheNextKindWithoutWaiting() {
        // 调试指令不看时刻：还没到第一次也照规矩挑——没村民留字条，留过以后只剩传言；给完、来了人就没有了。
        CrisisRecord record = CrisisSchedule.schedule(CrisisRecords.empty(),
                new CrisisSchedule.Raid("test:feud", "", 0, 3 * DAY), 2);
        assertTrue(CrisisSchedule.omenLeft(record));
        assertFalse(CrisisSchedule.omenDue(record));
        assertEquals(OmenStep.NOTE, CrisisSchedule.omenKind(record.getPending(), false));
        record = CrisisSchedule.omenDone(record, OmenStep.NOTE);
        assertEquals(OmenStep.WAIT, CrisisSchedule.omenKind(record.getPending(), false));
        assertEquals(OmenStep.RUMOR, CrisisSchedule.omenKind(record.getPending(), true));
        record = CrisisSchedule.omenDone(record, OmenStep.RUMOR);
        assertFalse(CrisisSchedule.omenLeft(record));
        CrisisRecord arrived = record.toBuilder().setPending(record.getPending().toBuilder().setArrived(true)
                .clearOmenAt().addOmenAt(DAY).addOmenAt(2 * DAY).setOmensGiven(0)).build();
        assertFalse(CrisisSchedule.omenLeft(arrived));
    }

    @Test
    public void omenCountIsSeeded() {
        // 同一世界、同一玩家、同一场总取同一个次数，并且在 [2, 3] 里。
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000042");
        long seed = CrisisSchedule.seed(7, player, 0);
        int count = CrisisSchedule.omenCount(seed, RULES.omen());
        assertEquals(count, CrisisSchedule.omenCount(CrisisSchedule.seed(7, player, 0), RULES.omen()));
        assertTrue(count >= 2 && count <= 3);
    }

    @Test
    public void omenCountVariesFromRaidToRaid() {
        // 同一玩家一场接一场（种子只差第几场）次数要变：二十场里 2 和 3 都出现。
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000042");
        Set<Integer> counts = new HashSet<>();
        for (int settled = 0; settled < RAIDS; settled++) {
            counts.add(CrisisSchedule.omenCount(CrisisSchedule.seed(7, player, settled), RULES.omen()));
        }
        assertEquals(Set.of(2, 3), counts);
    }

    private static CrisisRecord at(CrisisRecord record, long onlineTicks) {
        // 直接把在线时间拨到某一刻。
        return record.toBuilder().setOnlineTicks(onlineTicks).build();
    }
}
