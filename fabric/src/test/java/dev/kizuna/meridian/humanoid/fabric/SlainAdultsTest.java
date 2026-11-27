package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.CrisisRecord;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.SlainAdult;
import org.junit.Test;

import java.util.ArrayList;
import java.util.UUID;

import static org.junit.Assert.assertEquals;

public final class SlainAdultsTest {
    private static final int TOTAL_DEATHS = 40;
    private static final int RETAINED_DEATHS = 32;

    @Test
    public void keepsOnlyTheMostRecentDeathsInOrder() {
        var deaths = new ArrayList<SlainAdult>();
        var record = CrisisRecord.getDefaultInstance();
        for (int i = 1; i <= TOTAL_DEATHS; i++) {
            var death = SlainAdult.newBuilder()
                    .setVictim(new UUID(0, i).toString())
                    .setDiedAt(i)
                    .build();
            deaths.add(death);
            record = SlainAdults.append(record, death);
        }
        var expected = deaths.subList(TOTAL_DEATHS - RETAINED_DEATHS, TOTAL_DEATHS);
        assertEquals(expected, record.getSlainAdultsList());
    }
}
