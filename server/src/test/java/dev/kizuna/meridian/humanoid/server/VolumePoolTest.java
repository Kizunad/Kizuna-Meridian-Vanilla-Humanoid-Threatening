package dev.kizuna.meridian.humanoid.server;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.Test;

import static org.junit.Assert.*;

public final class VolumePoolTest {
    private static final UUID SUBJECT = new UUID(0, 1);
    private static final String FOREIGN = "minecraft:illusioner";
    private static final String AXE_OWNER = "minecraft:vindicator";
    private static final String BOW_OWNER = "minecraft:pillager";
    private static final String UPPER = "axe:upper";
    private static final String MIDDLE = "axe:middle";
    private static final VolumePool.Book AXE = new VolumePool.Book("axe:book", AXE_OWNER, 1,
            List.of(UPPER), Optional.of(MIDDLE));
    private static final VolumePool.Book ARCHERY = new VolumePool.Book("arrow:book", BOW_OWNER, 1,
            List.of("arrow:bow", "arrow:crossbow"), Optional.empty());
    private static final HumanoidTier TRUE = HumanoidTier.TRUE_QI;
    private static final HumanoidTier WANDERING = HumanoidTier.WANDERING_QI;
    private static final int SAMPLE = 10000;
    private static final double TOLERANCE = 0.025;

    @Test
    public void nativeBookCannotWinEvenWhenItsWeightIsTheOnlyPositiveOne() {
        var pool = new VolumePool(1, 1, 1, List.of(AXE));
        assertEquals(List.of(), pool.draw(SUBJECT, AXE_OWNER, TRUE, id -> true));
        assertEquals(List.of(UPPER, MIDDLE), pool.draw(SUBJECT, FOREIGN, TRUE, id -> true));
    }

    @Test
    public void onlyUsableVolumesOfOneBookCanWin() {
        var pool = new VolumePool(1, 1, 1, List.of(AXE, ARCHERY));
        assertEquals(List.of(), pool.draw(SUBJECT, FOREIGN, HumanoidTier.MORTAL, id -> true));
        assertEquals(List.of(), pool.draw(SUBJECT, FOREIGN, TRUE, id -> false));
        assertEquals(List.of(UPPER), pool.draw(SUBJECT, BOW_OWNER, TRUE, UPPER::equals));
        assertEquals(List.of(UPPER), pool.draw(SUBJECT, BOW_OWNER, WANDERING, id -> true));
        assertEquals(List.of("arrow:crossbow"), pool.draw(SUBJECT, FOREIGN, TRUE, "arrow:crossbow"::equals));
        var zero = new VolumePool.Book(AXE.id(), AXE_OWNER, 0, AXE.upper(), AXE.middle());
        var disabled = new VolumePool(1, 1, 1, List.of(zero));
        assertTrue(disabled.draw(SUBJECT, FOREIGN, TRUE, id -> true).isEmpty());
    }

    @Test
    public void defaultBookListAndTierRates() {
        var pool = HumanoidBrainConfigLoader.defaults().volumePool();
        var ids = pool.books().stream().flatMap(book -> book.upper().stream()).toList();
        assertEquals(Set.of("meridian_wayfarer:bow_upper", "meridian_wayfarer:crossbow",
                "meridian_door_breaker:upper", "meridian_peddler:road", "meridian_villager:outer",
                "meridian_evoker:nourish"), Set.copyOf(ids));
        var middle = pool.books().stream().flatMap(book -> book.middle().stream()).toList();
        assertEquals(Set.of("meridian_door_breaker:middle", "meridian_peddler:ambush",
                "meridian_evoker:infuse"), Set.copyOf(middle));
        int wandering = 0;
        int trueQi = 0;
        int progressed = 0;
        var single = new VolumePool(pool.wanderingChance(), pool.trueChance(), pool.middleChance(), List.of(AXE));
        for (int index = 0; index < SAMPLE; index++) {
            UUID id = new UUID(0, index);
            if (!single.draw(id, FOREIGN, WANDERING, any -> true).isEmpty()) {
                wandering++;
            }
            var selected = single.draw(id, FOREIGN, TRUE, any -> true);
            if (!selected.isEmpty()) {
                trueQi++;
                if (selected.contains(MIDDLE)) {
                    progressed++;
                }
            }
        }
        assertEquals(0.20, (double) wandering / SAMPLE, TOLERANCE);
        assertEquals(0.40, (double) trueQi / SAMPLE, TOLERANCE);
        assertEquals(0.50, (double) progressed / trueQi, TOLERANCE);
        System.out.printf("外卷固定桶 可用书=1 样本=%d 游炁=%d 真炁=%d 同系中卷=%d%n", SAMPLE, wandering, trueQi, progressed);
    }

    @Test
    public void bookWeightIsIndependentOfItsNumberOfUpperVolumesAndTableOrder() {
        var weighted = new VolumePool.Book(AXE.id(), AXE_OWNER, 3, AXE.upper(), AXE.middle());
        var pool = new VolumePool(1, 1, 0, List.of(weighted, ARCHERY));
        var reverse = new VolumePool(1, 1, 0, List.of(ARCHERY, weighted));
        int axes = 0;
        int bows = 0;
        for (int index = 0; index < SAMPLE; index++) {
            var id = new UUID(0, index);
            var chosen = pool.draw(id, FOREIGN, TRUE, any -> true);
            assertEquals(chosen, reverse.draw(id, FOREIGN, TRUE, any -> true));
            if (chosen.contains(UPPER)) {
                axes++;
            }
            if (chosen.contains("arrow:bow")) {
                bows++;
            }
        }
        assertEquals(0.75, (double) axes / SAMPLE, TOLERANCE);
        assertEquals(0.50, (double) bows / (SAMPLE - axes), TOLERANCE);
    }
}
