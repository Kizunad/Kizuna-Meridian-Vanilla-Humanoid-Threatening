package dev.kizuna.meridian.humanoid.server;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tomlj.Toml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 类人大脑配置：默认资源、导出与保留、白名单与范围拒绝，以及学卷分桶。 */
public class HumanoidBrainConfigLoaderTest {
    private static final String DEFAULT_TEXT = new HumanoidToml(HumanoidErrors.BRAIN)
            .resource(HumanoidBrainConfigLoader.DEFAULT_RESOURCE);
    private static final String VINDICATOR = "minecraft:vindicator";
    private static final String WANDERING_TRADER = "minecraft:wandering_trader";
    private static final String LOWER = "meridian_door_breaker:lower";
    private static final String EVOKER = "minecraft:evoker";
    private static final String NOURISH = "meridian_evoker:nourish";
    private static final String INFUSE = "meridian_evoker:infuse";
    private static final String BREATH = "meridian_basic_techniques:breath_volume";
    private static final String ROAD = "meridian_peddler:road";
    private static final String AMBUSH = "meridian_peddler:ambush";
    private static final String JOURNEY = "meridian_peddler:journey";
    private static final int DEFAULT_INTERVAL = 10;
    private static final int CUSTOM_INTERVAL = 7;
    private static final double DEFAULT_SAFE_DISTANCE = 12;
    private static final double DEFAULT_STAND_DISTANCE = 2;
    private static final double CUSTOM_STAND_DISTANCE = 3;
    private static final String STAND_DISTANCE_LINE = "cornered_stand_distance = 2.0";
    private static final double DEFAULT_ARMORED_CHEST = 6;
    private static final double CUSTOM_ARMORED_CHEST = 8;
    private static final String ARMORED_CHEST_LINE = "armored_chest_armor = 6";
    private static final double DEFAULT_UNPRESSED_SECONDS = 5;
    private static final double CUSTOM_SORTIE_DISTANCE = 24;
    private static final double CUSTOM_UNPRESSED_SECONDS = 8;
    private static final String UNPRESSED_LINE = "return_unpressed_seconds = 5.0";
    private static final String PILLAGER = "minecraft:pillager";
    private static final String ADDON_FIST = "meridian_test:fist";
    private static final String ADDON_SPECIES = "meridian_test:monk";
    private static final double ADDON_CHANCE = 0.25;
    /** 一份功法包学卷片段：村民游炁加一卷并重写一条已有的卷，另加一个配置里没有的物种。 */
    private static final String ADDON_VOLUMES = """
            [volumes."minecraft:villager"]
            wandering_qi = [
              { volume = "meridian_basic_techniques:breath_volume", chance = 0.5 },
              { volume = "meridian_test:fist", chance = 1.0 },
            ]

            [volumes."meridian_test:monk"]
            mortal = [ { volume = "meridian_test:fist", chance = 0.25 } ]
            """;
    private static final double DEFAULT_PILLAGER_NOTICE = 100;
    private static final double CUSTOM_PILLAGER_NOTICE = 64;
    private static final String NOTICE_SECTION = "[threat.notice_distance]\n\"minecraft:pillager\" = 100\n";
    private static final String SCORE_SECTION = "[threat.score]\n";
    private static final String PILLAGER_SCORE = "[threat.score.species.\"minecraft:pillager\"]\n";
    private static final ThreatScoring.Weights DEFAULT_SCORE = new ThreatScoring.Weights(
            1.0, 0.3, 0.1, 0.6, 0.5, 0.4, -0.5, -0.8, 0.35, 0.15, 2);
    private static final double PILLAGER_SCORE_DISTANCE = 0.4;
    private static final double LOWER_CHANCE = 0.1;
    private static final int DEFAULT_VOLUME_COUNT = 16;
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
        assertEquals(DEFAULT_STAND_DISTANCE, config.response().tactics().corneredStandDistance(), TOLERANCE);
        assertEquals(0.5, config.concurrency().holdRangeProbability(), TOLERANCE);
        assertTrue(config.concurrency().allowRecoveryMovement());
        assertEquals(DEFAULT_VOLUME_COUNT, config.volumes().allVolumes().size());
        var trueQi = config.volumes().species().get(VINDICATOR).get(HumanoidTier.TRUE_QI);
        assertEquals(TRUE_QI_VINDICATOR_VOLUMES, trueQi.size());
        assertTrue(trueQi.contains(new VolumeTable.Grant(LOWER, LOWER_CHANCE)));
        // 唤魔者两档都学五脏诀《养》，卷轴掉落按「死者会这一卷」判定。
        for (HumanoidTier tier : List.of(HumanoidTier.WANDERING_QI, HumanoidTier.TRUE_QI)) {
            assertTrue(config.volumes().species().get(EVOKER).get(tier).contains(new VolumeTable.Grant(NOURISH, 1)));
        }
        // 《附》只给真炁档，排在前置卷《养》后面：生物按表序授卷，前置卷没学全会被拒。
        var evoker = config.volumes().species().get(EVOKER);
        List<VolumeTable.Grant> trueQiEvoker = evoker.get(HumanoidTier.TRUE_QI);
        assertEquals(trueQiEvoker.indexOf(new VolumeTable.Grant(NOURISH, 1)) + 1,
                trueQiEvoker.indexOf(new VolumeTable.Grant(INFUSE, 1)));
        assertTrue(evoker.get(HumanoidTier.WANDERING_QI).stream().noneMatch(grant -> grant.volume().equals(INFUSE)));
        for (HumanoidSpecies species : HumanoidSpecies.values()) {
            assertTrue(config.volumes().species().containsKey(species.entityId()));
        }
    }

    @Test
    public void versionOneWithoutConcurrencyUsesCompatibleDefaults() {
        // 旧配置没有并行节时仍可加载，并使用与新默认资源相同的策略值。
        String legacy = DEFAULT_TEXT.replace("config_version = 2", "config_version = 1");
        String section = "[concurrency]\n"
                + "hold_range_probability = 0.5   # 接敌准备时保持跃步带的概率，"
                + "剩余情况直接接近\n"
                + "allow_recovery_movement = true # 调息时允许沿撤退路线继续移动\n\n";
        legacy = legacy.replace(section, "");
        HumanoidBrainConfig config = HumanoidBrainConfigLoader.parse(legacy);
        assertEquals(0.5, config.concurrency().holdRangeProbability(), TOLERANCE);
        assertTrue(config.concurrency().allowRecoveryMovement());
    }

    @Test
    public void oldFileWithoutStandDistanceRunsOnDefaultAndIsReported() {
        // 旧导出文件没有站定距离：按默认值运行，并列入启动提示的缺键清单；写了就用写的值，默认文件不缺键。
        String old = replace(STAND_DISTANCE_LINE, "");
        var tactics = HumanoidBrainConfigLoader.parse(old).response().tactics();
        assertEquals(DEFAULT_STAND_DISTANCE, tactics.corneredStandDistance(), TOLERANCE);
        assertEquals(List.of("response.cornered_stand_distance"), HumanoidBrainConfigLoader.missingDefaultKeys(old));
        assertEquals(List.of(), HumanoidBrainConfigLoader.missingDefaultKeys(DEFAULT_TEXT));
        String custom = replace(STAND_DISTANCE_LINE, "cornered_stand_distance = " + CUSTOM_STAND_DISTANCE);
        var customTactics = HumanoidBrainConfigLoader.parse(custom).response().tactics();
        assertEquals(CUSTOM_STAND_DISTANCE, customTactics.corneredStandDistance(), TOLERANCE);
    }

    @Test
    public void armoredChestThresholdComesFromTheFileAndOldFilesRunOnDefault() {
        // 「甲厚」的门槛默认是铁胸甲的 6 点；旧导出文件没有这个键时按默认值运行并列入缺键清单；
        // 写了就用写的值，零和负数在加载时拒绝。
        assertEquals(DEFAULT_ARMORED_CHEST, HumanoidBrainConfigLoader.defaults().response().armoredChestArmor(),
                TOLERANCE);
        String old = replace(ARMORED_CHEST_LINE, "");
        assertEquals(DEFAULT_ARMORED_CHEST, HumanoidBrainConfigLoader.parse(old).response().armoredChestArmor(),
                TOLERANCE);
        assertEquals(List.of("response.armored_chest_armor"), HumanoidBrainConfigLoader.missingDefaultKeys(old));
        String custom = replace(ARMORED_CHEST_LINE, "armored_chest_armor = " + CUSTOM_ARMORED_CHEST);
        assertEquals(CUSTOM_ARMORED_CHEST, HumanoidBrainConfigLoader.parse(custom).response().armoredChestArmor(),
                TOLERANCE);
        for (String bad : List.of("0", "-6")) {
            String text = replace(ARMORED_CHEST_LINE, "armored_chest_armor = " + bad);
            var failure = assertThrows(bad, IllegalArgumentException.class,
                    () -> HumanoidBrainConfigLoader.parse(text));
            assertTrue(bad, failure.getMessage().startsWith(HumanoidErrors.BRAIN));
        }
    }

    @Test
    public void oldFileWithoutStrikeStartDistanceRunsOnDefaultAndIsReported() {
        // 旧导出文件没有近身起手距离：按默认两格运行并列入缺键清单；写了就用写的值，零或负数拒绝。
        String line = "strike_start_distance = 2.0";
        String old = replace(line, "");
        assertEquals(2.0, HumanoidBrainConfigLoader.parse(old).response().tactics().strikeStartDistance(), TOLERANCE);
        assertEquals(List.of("response.strike_start_distance"), HumanoidBrainConfigLoader.missingDefaultKeys(old));
        String custom = replace(line, "strike_start_distance = 3.0");
        assertEquals(3.0, HumanoidBrainConfigLoader.parse(custom).response().tactics().strikeStartDistance(),
                TOLERANCE);
        assertThrows(RuntimeException.class,
                () -> HumanoidBrainConfigLoader.parse(replace(line, "strike_start_distance = 0")));
    }

    @Test
    public void oldFileWithoutKickStartDistanceRunsOnDefaultAndIsReported() {
        // 旧导出文件没有腿法起脚距离：按默认三格运行并列入缺键清单；写了就用写的值，零或负数拒绝。
        String line = "kick_start_distance = 3.0";
        String old = replace(line, "");
        assertEquals(3.0, HumanoidBrainConfigLoader.parse(old).response().tactics().kickStartDistance(), TOLERANCE);
        assertEquals(List.of("response.kick_start_distance"), HumanoidBrainConfigLoader.missingDefaultKeys(old));
        String custom = replace(line, "kick_start_distance = 2.5");
        assertEquals(2.5, HumanoidBrainConfigLoader.parse(custom).response().tactics().kickStartDistance(),
                TOLERANCE);
        assertThrows(RuntimeException.class,
                () -> HumanoidBrainConfigLoader.parse(replace(line, "kick_start_distance = 0")));
    }

    @Test
    public void oldFileWithoutUnpressedSecondsRunsOnDefaultAndIsReported() {
        // 旧导出文件没有「没被逼多少秒算拉开」：按默认值运行并列入缺键清单；写了就用写的值，零和负数在加载时拒绝。
        String old = replace(UNPRESSED_LINE, "");
        var recovery = HumanoidBrainConfigLoader.parse(old).assessment().recovery();
        assertEquals(DEFAULT_UNPRESSED_SECONDS, recovery.unpressedSeconds(), TOLERANCE);
        assertEquals(List.of("assessment.return_unpressed_seconds"), HumanoidBrainConfigLoader.missingDefaultKeys(old));
        String custom = replace(UNPRESSED_LINE, "return_unpressed_seconds = " + CUSTOM_UNPRESSED_SECONDS);
        var customRecovery = HumanoidBrainConfigLoader.parse(custom).assessment().recovery();
        assertEquals(CUSTOM_UNPRESSED_SECONDS, customRecovery.unpressedSeconds(), TOLERANCE);
        for (String bad : List.of("0", "-1.0")) {
            String text = replace(UNPRESSED_LINE, "return_unpressed_seconds = " + bad);
            var failure = assertThrows(bad, IllegalArgumentException.class,
                    () -> HumanoidBrainConfigLoader.parse(text));
            assertTrue(bad, failure.getMessage().startsWith(HumanoidErrors.BRAIN));
        }
    }

    @Test
    public void oldFileWithoutNoticeDistanceRunsOnDefaultAndIsReported() {
        // 旧导出文件没有盯上距离表：按默认表运行并列入缺键清单；写了就用写的表，空表表示都照原版。
        assertEquals(Map.of(PILLAGER, DEFAULT_PILLAGER_NOTICE),
                HumanoidBrainConfigLoader.defaults().threat().noticeDistances());
        String old = replace(NOTICE_SECTION, "");
        assertEquals(Map.of(PILLAGER, DEFAULT_PILLAGER_NOTICE),
                HumanoidBrainConfigLoader.parse(old).threat().noticeDistances());
        String missingKey = Toml.joinKeyPath(List.of("threat", "notice_distance", PILLAGER));
        assertEquals(List.of(missingKey), HumanoidBrainConfigLoader.missingDefaultKeys(old));
        String custom = replace(NOTICE_SECTION, "[threat.notice_distance]\n\"" + PILLAGER + "\" = "
                + CUSTOM_PILLAGER_NOTICE + "\n");
        assertEquals(Map.of(PILLAGER, CUSTOM_PILLAGER_NOTICE),
                HumanoidBrainConfigLoader.parse(custom).threat().noticeDistances());
        String empty = replace(NOTICE_SECTION, "[threat.notice_distance]\n");
        assertEquals(Map.of(), HumanoidBrainConfigLoader.parse(empty).threat().noticeDistances());
    }

    @Test
    public void threatScoreDefaultsAndSpeciesOverridesInherit() {
        // 默认表逐键取值；掠夺者只覆盖远近一项，其余照默认；没列出的物种整份用默认。
        ThreatScoring scoring = HumanoidBrainConfigLoader.defaults().threat().scoring();
        assertEquals(DEFAULT_SCORE, scoring.of(VINDICATOR));
        var pillager = scoring.of(PILLAGER);
        assertEquals(PILLAGER_SCORE_DISTANCE, pillager.distance(), TOLERANCE);
        assertEquals(DEFAULT_SCORE.recentDamage(), pillager.recentDamage(), TOLERANCE);
        assertEquals(DEFAULT_SCORE.unreachable(), pillager.unreachable(), TOLERANCE);
        String custom = replace(PILLAGER_SCORE, PILLAGER_SCORE + "stickiness = 0\n");
        assertEquals(0, HumanoidBrainConfigLoader.parse(custom).threat().scoring().of(PILLAGER).stickiness(),
                TOLERANCE);
    }

    @Test
    public void oldFileWithoutThreatScoreRunsOnDefaultAndIsReported() {
        // 旧导出文件没有打分表：按 JAR 默认运行，缺的键列入启动提示。
        String old = DEFAULT_TEXT.substring(0, DEFAULT_TEXT.indexOf("# 选敌打分"))
                + DEFAULT_TEXT.substring(DEFAULT_TEXT.indexOf("[assessment]"));
        assertEquals(HumanoidBrainConfigLoader.defaults().threat().scoring(),
                HumanoidBrainConfigLoader.parse(old).threat().scoring());
        var missing = HumanoidBrainConfigLoader.missingDefaultKeys(old);
        assertTrue(missing.toString(), missing.contains(Toml.joinKeyPath(List.of("threat", "score", "distance"))));
        assertTrue(missing.toString(), missing.contains(
                Toml.joinKeyPath(List.of("threat", "score", "species", PILLAGER, "distance"))));
    }

    @Test
    public void villagerSortieDefaultsOldFilesAndInvalidValues() {
        // 默认出击距离 16 格、5 秒再评估、人数权重 1。旧导出文件整节没有：按 JAR 默认运行，缺的键列入启动提示。
        // 距离和间隔必须为正，权重不能为负（0 可以，就是不看人数），未知键拒绝。
        var rules = HumanoidBrainConfigLoader.defaults().villager();
        assertEquals(HumanoidBrainConfig.VillagerRules.DEFAULTS, rules);
        String old = DEFAULT_TEXT.substring(0, DEFAULT_TEXT.indexOf("# 村民主动出击"))
                + DEFAULT_TEXT.substring(DEFAULT_TEXT.indexOf("[response]"));
        assertEquals(rules, HumanoidBrainConfigLoader.parse(old).villager());
        var missing = HumanoidBrainConfigLoader.missingDefaultKeys(old);
        assertTrue(missing.toString(), missing.contains(Toml.joinKeyPath(List.of("villager", "sortie_distance"))));
        String custom = replace("sortie_distance = 16 ", "sortie_distance = " + CUSTOM_SORTIE_DISTANCE + " ");
        custom = custom.replace("outnumbered_weight = 1.0 ", "outnumbered_weight = 0 ");
        var customRules = HumanoidBrainConfigLoader.parse(custom).villager();
        assertEquals(CUSTOM_SORTIE_DISTANCE, customRules.sortieDistance(), TOLERANCE);
        assertEquals(0, customRules.outnumberedWeight(), TOLERANCE);
        var invalid = Map.of(
                "距离为 0", replace("sortie_distance = 16 ", "sortie_distance = 0 "),
                "间隔为负", replace("sortie_reconsider_seconds = 5 ", "sortie_reconsider_seconds = -1 "),
                "权重为负", replace("outnumbered_weight = 1.0 ", "outnumbered_weight = -0.5 "),
                "未知键", replace("[villager]\n", "[villager]\nbravery = 1\n"),
                "缺键", replace("sortie_distance = 16 ", "# sortie_distance = 16 "));
        invalid.forEach((label, text) -> {
            var failure = assertThrows(label, IllegalArgumentException.class,
                    () -> HumanoidBrainConfigLoader.parse(text));
            assertTrue(label, failure.getMessage().startsWith(HumanoidErrors.BRAIN));
        });
    }

    @Test
    public void guardApproachSpeedReplacesReloadSpeedAndValidatesValues() {
        var defaults = HumanoidBrainConfigLoader.defaults().villager();
        String missing = replace("guard_approach_speed = 0.805", "# guard_approach_speed = 0.805");
        assertEquals(defaults, HumanoidBrainConfigLoader.parse(missing).villager());
        String legacy = replace("guard_approach_speed = 0.805", "reload_approach_speed = 0.75");
        assertEquals(defaults, HumanoidBrainConfigLoader.parse(legacy).villager());
        String custom = replace("guard_approach_speed = 0.805", "guard_approach_speed = 1");
        assertEquals(1, HumanoidBrainConfigLoader.parse(custom).villager().guardApproachSpeed(), TOLERANCE);
        for (String invalid : List.of("0", "-1", "nan", "inf")) {
            String text = replace("guard_approach_speed = 0.805", "guard_approach_speed = " + invalid);
            assertThrows(IllegalArgumentException.class, () -> HumanoidBrainConfigLoader.parse(text));
        }
    }

    @Test
    public void invalidThreatScoresAreRejected() {
        // 扣分项为正、加分项为负、未知键、物种无命名空间、物种表未知键和默认表缺键都在加载时拒绝。
        var invalid = Map.of(
                "扣分项为正", replace("summon = -0.5", "summon = 0.5"),
                "加分项为负", replace("stickiness = 0.35", "stickiness = -0.35"),
                "未知键", replace(SCORE_SECTION, SCORE_SECTION + "loot = 1\n"),
                "无命名空间", replace(PILLAGER_SCORE, "[threat.score.species.pillager]\n"),
                "物种未知键", replace(PILLAGER_SCORE, PILLAGER_SCORE + "loot = 1\n"),
                "物种扣分为正", replace(PILLAGER_SCORE, PILLAGER_SCORE + "unreachable = 0.8\n"),
                "缺键", replace("min_hold_seconds = 2 ", "# min_hold_seconds = 2 "));
        invalid.forEach((label, text) -> {
            var failure = assertThrows(label, IllegalArgumentException.class,
                    () -> HumanoidBrainConfigLoader.parse(text));
            assertTrue(label, failure.getMessage().startsWith(HumanoidErrors.BRAIN));
        });
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
    public void wanderingTraderReceivesPeddlerVolumesByRealm() {
        // 流浪商人两档都固定学上卷，真炁再学中卷，下卷保留一成概率。
        HumanoidBrainConfig config = HumanoidBrainConfigLoader.defaults();
        var wandering = config.volumes().volumes(WANDERING_TRADER, HumanoidTier.WANDERING_QI, UUID.randomUUID());
        var trueQi = config.volumes().volumes(WANDERING_TRADER, HumanoidTier.TRUE_QI, UUID.randomUUID());
        assertTrue(wandering.contains(ROAD));
        assertTrue(trueQi.contains(ROAD));
        assertTrue(trueQi.contains(AMBUSH));
        var grants = config.volumes().species().get(WANDERING_TRADER).get(HumanoidTier.TRUE_QI);
        assertTrue(grants.contains(new VolumeTable.Grant(JOURNEY, LOWER_CHANCE)));
    }

    @Test
    public void pillagerReceivesWayfarerVolumesByTier() {
        // 游炁固定学弩卷；真炁再学弓上卷，一成学弓下卷；凡人不学，其他物种不受影响。
        String pillager = HumanoidSpecies.PILLAGER.entityId();
        String crossbow = "meridian_wayfarer:crossbow";
        String upper = "meridian_wayfarer:bow_upper";
        String lower = "meridian_wayfarer:bow_lower";
        VolumeTable table = HumanoidBrainConfigLoader.defaults().volumes();
        UUID entity = UUID.nameUUIDFromBytes("wayfarer".getBytes(StandardCharsets.UTF_8));
        var wandering = table.volumes(pillager, HumanoidTier.WANDERING_QI, entity);
        assertTrue(wandering.contains(crossbow));
        assertFalse(wandering.contains(upper));
        assertFalse(wandering.contains(lower));
        var trueQi = table.volumes(pillager, HumanoidTier.TRUE_QI, entity);
        assertTrue(trueQi.contains(crossbow));
        assertTrue(trueQi.contains(upper));
        var grants = table.species().get(pillager).get(HumanoidTier.TRUE_QI);
        assertTrue(grants.contains(new VolumeTable.Grant(lower, LOWER_CHANCE)));
        assertTrue(table.volumes(pillager, HumanoidTier.MORTAL, entity).isEmpty());
        table.species().forEach((species, tiers) -> {
            if (!species.equals(pillager)) {
                tiers.values().forEach(values -> assertTrue(values.stream()
                        .noneMatch(grant -> grant.volume().startsWith("meridian_wayfarer:"))));
            }
        });
    }

    @Test
    public void volumeOrderRequiresPrerequisitesListedEarlier() {
        // 默认表的《附》排在《养》后面能过；颠倒或这一档缺《养》都拒绝，报清是哪一卷排在哪一卷前面。
        Function<String, List<String>> prerequisites = volume -> volume.equals(INFUSE) ? List.of(NOURISH) : List.of();
        HumanoidBrainConfigLoader.defaults().volumes().requireOrdered(prerequisites);
        String pair = grantLine(NOURISH) + grantLine(INFUSE);
        VolumeTable reversed = HumanoidBrainConfigLoader.parse(replace(pair, grantLine(INFUSE) + grantLine(NOURISH)))
                .volumes();
        VolumeTable missing = HumanoidBrainConfigLoader.parse(replace(pair, grantLine(INFUSE))).volumes();
        String later = assertThrows(IllegalArgumentException.class, () -> reversed.requireOrdered(prerequisites))
                .getMessage();
        String absent = assertThrows(IllegalArgumentException.class, () -> missing.requireOrdered(prerequisites))
                .getMessage();
        assertTrue(later, later.contains(EVOKER + " true_qi 的学卷 " + INFUSE + " 排在它的前置卷 " + NOURISH + " 前面"));
        assertTrue(absent, absent.contains(EVOKER + " true_qi 的学卷 " + INFUSE + " 要先学前置卷 " + NOURISH));
    }

    private static String grantLine(String volume) {
        // 默认表里一卷一行的写法。
        return "  { volume = \"" + volume + "\", chance = 1.0 },\n";
    }

    @Test
    public void extraEntityIdsAreAccepted() {
        // 其他命名空间的实体 ID 可以另列学卷；未列档位不学卷。
        String text = DEFAULT_TEXT + "\n[volumes.\"dummy:humanoid\"]\ntrue_qi = [{ volume = \"" + BREATH
                + "\", chance = 1.0 }]\n";
        VolumeTable volumes = HumanoidBrainConfigLoader.parse(text).volumes();
        UUID entity = UUID.nameUUIDFromBytes("dummy".getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(BREATH), volumes.volumes("dummy:humanoid", HumanoidTier.TRUE_QI, entity));
        assertEquals(List.of(), volumes.volumes("dummy:humanoid", HumanoidTier.WANDERING_QI, entity));
        assertEquals(List.of(), volumes.volumes("dummy:unlisted", HumanoidTier.TRUE_QI, entity));
    }

    @Test
    public void mortalTierIsEmptyByDefaultAndCanBeListed() {
        // 默认表各物种都不写凡人档，凡人一卷不学；游炁照旧学满档。配置可以另写 mortal 档。
        UUID entity = UUID.nameUUIDFromBytes("mortal".getBytes(StandardCharsets.UTF_8));
        VolumeTable defaults = HumanoidBrainConfigLoader.defaults().volumes();
        for (HumanoidSpecies species : HumanoidSpecies.values()) {
            assertEquals(List.of(), defaults.volumes(species.entityId(), HumanoidTier.MORTAL, entity));
            assertFalse(defaults.volumes(species.entityId(), HumanoidTier.WANDERING_QI, entity).isEmpty());
        }
        String text = replace("[volumes.\"minecraft:witch\"]",
                "[volumes.\"minecraft:witch\"]\nmortal = [{ volume = \"" + BREATH + "\", chance = 1.0 }]");
        VolumeTable listed = HumanoidBrainConfigLoader.parse(text).volumes();
        assertEquals(List.of(BREATH), listed.volumes("minecraft:witch", HumanoidTier.MORTAL, entity));
    }

    @Test
    public void invalidConfigsAreRejected() {
        // 版本、未知字段、缺物种、概率越界、无命名空间卷、未知档位、负权重、零失败次数和零站定距离都在加载时拒绝。
        var invalid = Map.of(
                "版本", replace("config_version = 2", "config_version = 3"),
                "顶层未知字段", replace("log_decisions = false", "log_decisions = false\nunknown = 1"),
                "章节未知字段", replace("[threat]", "[threat]\nunknown = 1"),
                "缺物种", DEFAULT_TEXT.substring(0, DEFAULT_TEXT.indexOf("[volumes.\"minecraft:illusioner\"]")),
                "概率越界", replace("meridian_peddler:journey\", chance = 0.1",
                        "meridian_peddler:journey\", chance = 1.5"),
                "无命名空间卷", replace(LOWER, "lower"),
                "未知档位", replace("[volumes.\"minecraft:witch\"]", "[volumes.\"minecraft:witch\"]\ngolden_core = []"),
                "负权重", replace("qi_weight = 0.25", "qi_weight = -0.25"),
                "零失败次数", replace("failure_limit = 2", "failure_limit = 0"),
                "零站定距离", replace(STAND_DISTANCE_LINE, "cornered_stand_distance = 0"));
        invalid.forEach((label, text) -> {
            var failure = assertThrows(label, IllegalArgumentException.class,
                    () -> HumanoidBrainConfigLoader.parse(text));
            assertTrue(label, failure.getMessage().startsWith(HumanoidErrors.BRAIN));
        });
    }

    @Test
    public void invalidNoticeDistancesAreRejected() {
        // 盯上距离超过原版视线上限 128 格、为零或实体 ID 不带命名空间，都在加载时拒绝。
        var invalid = Map.of(
                "超过视线上限", replace(NOTICE_SECTION, "[threat.notice_distance]\n\"" + PILLAGER + "\" = 129\n"),
                "为零", replace(NOTICE_SECTION, "[threat.notice_distance]\n\"" + PILLAGER + "\" = 0\n"),
                "无命名空间", replace(NOTICE_SECTION, "[threat.notice_distance]\npillager = 100\n"));
        invalid.forEach((label, text) -> {
            var failure = assertThrows(label, IllegalArgumentException.class,
                    () -> HumanoidBrainConfigLoader.parse(text));
            assertTrue(label, failure.getMessage().startsWith(HumanoidErrors.BRAIN));
        });
    }

    @Test
    public void addonVolumesJoinAfterConfiguredOnesAndConfiguredGrantsWin() {
        // 功法包带的学卷片段接在配置文件那一档的后面；配置里已经写了的卷照配置的概率，不重复加；没写过的物种整张加进来。
        String villager = HumanoidSpecies.VILLAGER.entityId();
        VolumeTable configured = HumanoidBrainConfigLoader.defaults().volumes();
        VolumeTable merged = configured.plus(HumanoidBrainConfigLoader.addonVolumes(ADDON_VOLUMES));
        var wandering = merged.species().get(villager).get(HumanoidTier.WANDERING_QI);
        var before = configured.species().get(villager).get(HumanoidTier.WANDERING_QI);
        assertEquals(before, wandering.subList(0, before.size()));
        assertEquals(List.of(new VolumeTable.Grant(ADDON_FIST, 1)), wandering.subList(before.size(), wandering.size()));
        assertEquals(configured.species().get(villager).get(HumanoidTier.TRUE_QI),
                merged.species().get(villager).get(HumanoidTier.TRUE_QI));
        assertEquals(Map.of(HumanoidTier.MORTAL, List.of(new VolumeTable.Grant(ADDON_FIST, ADDON_CHANCE))),
                merged.species().get(ADDON_SPECIES));
        assertEquals(configured.species().get(VINDICATOR), merged.species().get(VINDICATOR));
    }

    @Test
    public void addonVolumesAcceptOnlyTheVolumeTable() {
        // 片段只能写学卷表：带别的节、档位写错、卷 ID 不带命名空间都拒绝。
        assertThrows(IllegalArgumentException.class,
                () -> HumanoidBrainConfigLoader.addonVolumes("decision_interval_ticks = 4\n" + ADDON_VOLUMES));
        assertThrows(IllegalArgumentException.class,
                () -> HumanoidBrainConfigLoader.addonVolumes(ADDON_VOLUMES.replace("wandering_qi", "adept")));
        assertThrows(IllegalArgumentException.class,
                () -> HumanoidBrainConfigLoader.addonVolumes(ADDON_VOLUMES.replace(ADDON_FIST, "fist")));
        assertThrows(IllegalArgumentException.class, () -> HumanoidBrainConfigLoader.addonVolumes(""));
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
