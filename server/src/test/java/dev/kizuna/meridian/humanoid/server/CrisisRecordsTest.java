package dev.kizuna.meridian.humanoid.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.Any;
import com.google.protobuf.StringValue;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.CrisisRecord;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Feud;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Pending;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import org.junit.Test;

/** 招敌记录：写进身体再读回来一样；坏记录读写两头都拒绝。 */
public class CrisisRecordsTest {
    @Test
    public void newPlayerHasAnEmptyRecord() {
        // 身体里没有这一项就是新玩家。
        CrisisRecord record = CrisisRecords.read(BodySnapshot.getDefaultInstance());
        assertEquals(CrisisRecords.SCHEMA_VERSION, record.getSchemaVersion());
        assertEquals(0, record.getOnlineTicks());
        assertTrue(!record.hasPending() && !record.hasGrudge());
    }

    @Test
    public void writtenRecordReadsBack() {
        // 写进活动的附属进度，再当作身体里的一项读回来，内容一字不差。
        CrisisRecord record = CrisisRecords.empty().toBuilder().setOnlineTicks(48_000).setHeat(12.5)
                .addFeuds(Feud.newBuilder().setKey("test:orphan#0").setStage(1).setStarted(true).setDueAt(90_000))
                .setPending(Pending.newBuilder().setParty("test:feud").setFeud("test:orphan#0").setScheduledAt(48_000)
                        .setStrikeAt(120_000).addOmenAt(72_000).addOmenAt(96_000))
                .build();
        BodyActivity.Builder activity = BodyActivity.newBuilder();
        CrisisRecords.write(activity, record);
        BodySnapshot body = BodySnapshot.newBuilder()
                .putAddonProgress(CrisisRecords.PROGRESS_KEY, activity.getProgressOrThrow(CrisisRecords.PROGRESS_KEY))
                .build();
        assertEquals(record, CrisisRecords.read(body));
    }

    @Test
    public void corruptRecordsAreRejected() {
        // 版本不对、类型不对、指向不存在的仇家、征兆晚于来人，都是存档损坏。
        assertCorrupt(Any.pack(CrisisRecords.empty().toBuilder().setSchemaVersion(9).build()));
        assertCorrupt(Any.pack(StringValue.of("x")));
        assertCorrupt(Any.pack(CrisisRecords.empty().toBuilder()
                .setPending(Pending.newBuilder().setParty("test:feud").setFeud("test:gone#0")).build()));
        assertCorrupt(Any.pack(CrisisRecords.empty().toBuilder()
                .setPending(Pending.newBuilder().setParty("test:feud").setStrikeAt(10).addOmenAt(20)).build()));
        assertThrows(IllegalArgumentException.class, () -> CrisisRecords.write(BodyActivity.newBuilder(),
                CrisisRecords.empty().toBuilder().setHeat(-1).build()));
    }

    private static void assertCorrupt(Any stored) {
        // 读的时候报错，不当成新玩家。
        BodySnapshot body = BodySnapshot.newBuilder().putAddonProgress(CrisisRecords.PROGRESS_KEY, stored).build();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CrisisRecords.read(body));
        assertTrue(failure.getMessage(), failure.getMessage().startsWith("招敌记录无效"));
    }
}
