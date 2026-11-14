package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidBrainConfigLoader;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.humanoid.server.VolumeTable;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Realm;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.VolumeSource;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class HumanoidVolumeSourcesTest {
    private static final int DEFAULT_SOURCES = 60;
    private static final int DEFAULT_PAIRS = 34;
    private static final double RARE = 0.1;

    @Test
    public void defaultTableRegistersEveryGrantedVolumeOnce() {
        // 缺省学卷表的游炁、真炁两档逐条登记：共 60 条授卷，去掉两档重复是 34 对「物种×卷」。
        var sources = HumanoidVolumeSources.sources(HumanoidBrainConfigLoader.defaults().volumes());
        assertEquals(DEFAULT_SOURCES, sources.size());
        long pairs = sources.stream().map(source -> source.getKnower().getEntityType() + " " + source.getVolumeId())
                .distinct().count();
        assertEquals(DEFAULT_PAIRS, pairs);
        assertTrue(sources.stream().allMatch(source -> source.getKnower().getRealm() != Realm.REALM_UNSPECIFIED));
    }

    @Test
    public void tiersBecomeRealmsAndMortalOrZeroChanceAreSkipped() {
        // 游炁档、真炁档换成境界；凡人档和概率为零的卷不登记；概率原样写进占比。
        var table = new VolumeTable(Map.of("minecraft:pillager", Map.of(
                HumanoidTier.MORTAL, List.of(new VolumeTable.Grant("example:mortal", 1)),
                HumanoidTier.WANDERING_QI, List.of(new VolumeTable.Grant("example:never", 0)),
                HumanoidTier.TRUE_QI, List.of(new VolumeTable.Grant("example:rare", RARE)))));
        List<VolumeSource> sources = HumanoidVolumeSources.sources(table);
        assertEquals(1, sources.size());
        var knower = sources.getFirst().getKnower();
        assertEquals("example:rare", sources.getFirst().getVolumeId());
        assertEquals("minecraft:pillager", knower.getEntityType());
        assertEquals(Realm.TRUE_QI, knower.getRealm());
        assertEquals(RARE, knower.getShare(), 0);
    }
}
