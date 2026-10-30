package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.core.Realm;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 类人大脑配置：默认资源、导出与保留、白名单与范围拒绝，以及学卷分桶。 */
public class HumanoidBrainConfigLoaderTest {
    private static final String DEFAULT_TEXT = new HumanoidToml(HumanoidErrors.BRAIN)
            .resource(HumanoidBrainConfigLoader.DEFAULT_RESOURCE);
    private static final String VINDICATOR = "minecraft:vindicator";
    private static final String LOWER = "meridian_door_breaker:lower";
    private static final String BREATH = "meridian_basic_techniques:breath_volume";
    private static final int DEFAULT_INTERVAL = 10;
    private static final int CUSTOM_INTERVAL = 7;
    private static final double DEFAULT_SAFE_DISTANCE = 12;
    private static final double LOWER_CHANCE = 0.1;
    private static final int DEFAULT_VOLUME_COUNT = 6;
    private static final int TRUE_QI_VINDICATOR_VOLUMES = 6;
    private static final int POPULATION = 2000;
    private static final double RATE_TOLERANCE = 0.03;
    private static final double TOLERANCE = 1e-9;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void defaultsMatchShippedValues() {
        // 默认资源给出初版节奏、安全距离与卫道士学卷表，安全距离同时用于撤退和回归。
        HumanoidBrainConfig config = HumanoidBrainConfigLoader.defaults();
        assertEquals(DEFAULT_INTERVAL, config.schedule().decisionIntervalTicks());
        assertEquals(HumanoidBrainConfig.DecisionLog.QUIET, config.schedule().log());
        assertEquals(DEFAULT_SAFE_DISTANCE, config.response().tactics().safeDistance(), TOLERANCE);
        assertEquals(DEFAULT_SAFE_DISTANCE, config.assessment().recovery().distance(), TOLERANCE);
        assertEquals(DEFAULT_VOLUME_COUNT, config.volumes().allVolumes().size());
        var trueQi = config.volumes().species().get(VINDICATOR).get(Realm.TRUE_QI);
        assertEquals(TRUE_QI_VINDICATOR_VOLUMES, trueQi.size());
        assertTrue(trueQi.contains(new VolumeTable.Grant(LOWER, LOWER_CHANCE)));
        for (HumanoidSpecies species : HumanoidSpecies.values()) {
            assertTrue(config.volumes().species().containsKey(species.entityId()));
        }
    }

    @Test
    public void loadExportsMissingFileAndKeepsExisting() throws IOException {
        // 首次加载写出默认文件；已有文件按其内容读取，不被覆盖。
        Path exported = folder.getRoot().toPath().resolve("config/meridian-humanoid-brain.toml");
        HumanoidBrainConfigLoader.load(exported);
        assertEquals(DEFAULT_TEXT, Files.readString(exported, StandardCharsets.UTF_8));
        Path custom = folder.newFile("custom.toml").toPath();
        Files.writeString(custom, replace("decision_interval_ticks = " + DEFAULT_INTERVAL,
                "decision_interval_ticks = " + CUSTOM_INTERVAL));
        assertEquals(CUSTOM_INTERVAL, HumanoidBrainConfigLoader.load(custom).schedule().decisionIntervalTicks());
    }

    @Test
    public void extraEntityIdsAreAccepted() {
        // 其他命名空间的实体 ID 可以另列学卷；未列档位不学卷。
        String text = DEFAULT_TEXT + "\n[volumes.\"dummy:humanoid\"]\ntrue_qi = [{ volume = \"" + BREATH
                + "\", chance = 1.0 }]\n";
        VolumeTable volumes = HumanoidBrainConfigLoader.parse(text).volumes();
        UUID entity = UUID.nameUUIDFromBytes("dummy".getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(BREATH), volumes.volumes("dummy:humanoid", Realm.TRUE_QI, entity));
        assertEquals(List.of(), volumes.volumes("dummy:humanoid", Realm.WANDERING_QI, entity));
        assertEquals(List.of(), volumes.volumes("dummy:unlisted", Realm.TRUE_QI, entity));
    }

    @Test
    public void invalidConfigsAreRejected() {
        // 版本、未知字段、缺物种、概率越界、无命名空间卷、未知境界、负权重和零失败次数都在加载时拒绝。
        var invalid = Map.of(
                "版本", replace("config_version = 1", "config_version = 2"),
                "顶层未知字段", replace("log_decisions = false", "log_decisions = false\nunknown = 1"),
                "章节未知字段", replace("[threat]", "[threat]\nunknown = 1"),
                "缺物种", DEFAULT_TEXT.substring(0, DEFAULT_TEXT.indexOf("[volumes.\"minecraft:illusioner\"]")),
                "概率越界", replace("chance = 0.1", "chance = 1.5"),
                "无命名空间卷", replace(LOWER, "lower"),
                "未知境界", replace("[volumes.\"minecraft:witch\"]", "[volumes.\"minecraft:witch\"]\nmortal = []"),
                "负权重", replace("qi_weight = 0.25", "qi_weight = -0.25"),
                "零失败次数", replace("failure_limit = 2", "failure_limit = 0"));
        invalid.forEach((label, text) -> {
            var failure = assertThrows(label, IllegalArgumentException.class,
                    () -> HumanoidBrainConfigLoader.parse(text));
            assertTrue(label, failure.getMessage().startsWith(HumanoidErrors.BRAIN));
        });
    }

    @Test
    public void bucketsAreDeterministicAndFollowChance() {
        // 同一实体对同一卷总是同一结果；必得、不得与一成概率在大量实体上符合设定。
        var always = new VolumeTable.Grant(BREATH, 1);
        var never = new VolumeTable.Grant(BREATH, 0);
        var rare = new VolumeTable.Grant(LOWER, LOWER_CHANCE);
        int granted = 0;
        for (int index = 0; index < POPULATION; index++) {
            UUID entity = UUID.nameUUIDFromBytes(("humanoid-" + index).getBytes(StandardCharsets.UTF_8));
            assertTrue(VolumeTable.granted(always, entity));
            assertFalse(VolumeTable.granted(never, entity));
            boolean first = VolumeTable.granted(rare, entity);
            assertEquals(first, VolumeTable.granted(rare, entity));
            if (first) {
                granted++;
            }
        }
        assertEquals(LOWER_CHANCE, (double) granted / POPULATION, RATE_TOLERANCE);
    }

    private static String replace(String original, String replacement) {
        // 默认文本中目标片段必须恰好出现一次，避免改错位置。
        int first = DEFAULT_TEXT.indexOf(original);
        if (first < 0 || first != DEFAULT_TEXT.lastIndexOf(original)) {
            throw new IllegalStateException("默认配置中片段不唯一: " + original);
        }
        return DEFAULT_TEXT.replace(original, replacement);
    }
}
