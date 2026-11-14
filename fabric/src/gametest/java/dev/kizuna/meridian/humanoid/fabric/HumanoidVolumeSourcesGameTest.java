package dev.kizuna.meridian.humanoid.fabric;

import static dev.kizuna.meridian.humanoid.fabric.HumanoidGameTestTicks.DEFAULT_TIMEOUT;

import dev.kizuna.meridian.protocol.pb.BodyMessages.Realm;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.VolumeKnower;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.VolumeSource;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * 完整装配下功法谱的来源读并好的学卷表：功法包在 fabric.mod.json 声明的学卷片段也登记出去。
 *
 * <p>只核片段里的那几条，不数总条数：总条数随装上的功法包变化，缺省配置的条数由单元测试数。</p>
 */
public final class HumanoidVolumeSourcesGameTest {
    private static final String VILLAGER = "minecraft:villager";
    private static final String SLEEVE_FIST_OUTER = "meridian_villager:outer";

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void addonVolumeFragmentsBecomeCodexSources(GameTestHelper context) {
        // 袖手拳在自己的片段里给村民游炁、真炁两档都写了上卷；登记出去的来源里两档都要在。
        var realms = HumanoidVolumeSources.sources(HumanoidBrain.config().volumes()).stream()
                .filter(source -> source.getVolumeId().equals(SLEEVE_FIST_OUTER))
                .map(VolumeSource::getKnower)
                .filter(knower -> knower.getEntityType().equals(VILLAGER))
                .map(VolumeKnower::getRealm)
                .toList();
        context.assertTrue(realms.equals(List.of(Realm.WANDERING_QI, Realm.TRUE_QI)),
                "袖手拳片段里村民的上卷没成为功法谱来源：" + realms);
        context.succeed();
    }
}
