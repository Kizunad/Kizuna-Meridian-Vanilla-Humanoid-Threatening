package dev.kizuna.meridian.humanoid.server;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/** 类人大脑 TOML 读取器：字段白名单逐层检查，范围非法或缺物种时在加载时拒绝。 */
public final class HumanoidBrainConfigLoader {
    public static final String DEFAULT_RESOURCE = "/meridian-humanoid-brain.toml";
    private static final int CURRENT_CONFIG_VERSION = 2;
    private static final int MIN_SUPPORTED_CONFIG_VERSION = 1;
    private static final String CORNERED_STAND_DISTANCE = "cornered_stand_distance";
    private static final String VOLUMES = "volumes";
    private static final HumanoidToml TOML = new HumanoidToml(HumanoidErrors.BRAIN);
    private static final Logger LOGGER = System.getLogger("meridian_humanoid_brain_config");

    private HumanoidBrainConfigLoader() {
        // 配置加载器不持有服务器实例或可变配置。
    }

    public static HumanoidBrainConfig defaults() {
        // JAR 资源是默认值唯一来源。
        return parse(TOML.resource(DEFAULT_RESOURCE));
    }

    public static HumanoidBrainConfig load(Path path) {
        // 首次加载导出默认文件；已有文件只读取，不覆盖运营方调整。
        boolean existing = path != null && Files.exists(path);
        String text = TOML.readOrExport(path, DEFAULT_RESOURCE);
        HumanoidBrainConfig loaded = parse(text);
        if (existing) {
            logMissingDefaultKeys(path, missingDefaultKeys(text));
            logMissingDefaultVolumes(path, loaded.volumes());
        }
        return loaded;
    }

    static List<String> missingDefaultKeys(String text) {
        // 默认资源里有、给定文本里缺的键；能加载的文本只会缺可省键。学卷表按卷单独比较。
        TomlTable configured = TOML.parse(text);
        return TOML.parse(TOML.resource(DEFAULT_RESOURCE)).keyPathSet().stream()
                .filter(keyPath -> !keyPath.getFirst().equals(VOLUMES))
                .filter(keyPath -> !configured.contains(keyPath))
                .map(Toml::joinKeyPath)
                .sorted()
                .toList();
    }

    private static void logMissingDefaultKeys(Path path, List<String> missing) {
        // 旧导出文件不会自动补新键，启动时提示一次，缺的键按 JAR 默认值运行。
        if (!missing.isEmpty()) {
            LOGGER.log(Level.WARNING, "类人大脑配置 " + path + " 缺少默认键 " + missing
                    + "；这些键按默认值运行，请照默认文件补入，程序不会自动修改文件");
        }
    }

    private static void logMissingDefaultVolumes(Path path, VolumeTable configured) {
        // 只提示默认表中新出现且运营文件缺少的卷，不自动修改已有文件。
        VolumeTable defaults = defaults().volumes();
        for (var species : defaults.species().entrySet()) {
            var configuredTiers = configured.species().getOrDefault(species.getKey(), Map.of());
            for (var tier : species.getValue().entrySet()) {
                var configuredGrants = configuredTiers.getOrDefault(tier.getKey(), List.of());
                Set<String> configuredVolumes = configuredGrants.stream()
                        .map(VolumeTable.Grant::volume).collect(Collectors.toSet());
                List<String> missing = tier.getValue().stream().map(VolumeTable.Grant::volume)
                        .filter(volume -> !configuredVolumes.contains(volume)).distinct().toList();
                if (!missing.isEmpty()) {
                    LOGGER.log(Level.WARNING, "学卷配置 " + path + " 缺少物种 " + species.getKey() + " 档位 "
                            + tier.getKey().id() + " 的默认卷 " + missing
                            + "；请将这些卷条目补入对应数组，程序不会自动修改文件");
                }
            }
        }
    }

    public static HumanoidBrainConfig parse(String text) {
        // 版本只向前兼容一个小版本；旧配置缺少并行节时使用稳定默认策略。
        var parsed = TOML.parse(text);
        TOML.checkKeys(parsed, Set.of("config_version", "decision_interval_ticks", "log_decisions", "threat",
                "assessment", "response", VOLUMES, "concurrency"), "");
        int version = TOML.integer(parsed, "config_version", "config_version");
        if (version < MIN_SUPPORTED_CONFIG_VERSION || version > CURRENT_CONFIG_VERSION) {
            throw TOML.invalid("config_version 必须位于 " + MIN_SUPPORTED_CONFIG_VERSION + "～"
                    + CURRENT_CONFIG_VERSION + "，请删除旧文件或按新默认值合并");
        }
        var schedule = new HumanoidBrainConfig.Schedule(
                TOML.integer(parsed, "decision_interval_ticks", "decision_interval_ticks"), log(parsed));
        var threat = threat(TOML.table(parsed, "threat", "threat"));
        var assessment = assessment(TOML.table(parsed, "assessment", "assessment"), threat.safeDistance());
        var response = response(TOML.table(parsed, "response", "response"), threat.safeDistance());
        var volumes = volumes(TOML.table(parsed, VOLUMES, VOLUMES));
        var concurrency = concurrency(parsed);
        return new HumanoidBrainConfig(schedule, threat, assessment, response, volumes, concurrency);
    }

    private static HumanoidBrainConfig.ConcurrencyRules concurrency(TomlTable parsed) {
        // 版本 1 没有并行节；缺失时保留与旧行为一致的随机保持带和可移动调息。
        Object raw = parsed.get(List.of("concurrency"));
        if (!(raw instanceof TomlTable table)) {
            return HumanoidBrainConfig.ConcurrencyRules.DEFAULTS;
        }
        TOML.checkKeys(table, Set.of("hold_range_probability", "allow_recovery_movement"), "concurrency.");
        double probability = ratio(table, "hold_range_probability", "concurrency");
        Object movement = table.get(List.of("allow_recovery_movement"));
        if (!(movement instanceof Boolean allowRecoveryMovement)) {
            throw TOML.invalid("concurrency.allow_recovery_movement 必须是布尔值");
        }
        return new HumanoidBrainConfig.ConcurrencyRules(probability, allowRecoveryMovement);
    }

    private static HumanoidBrainConfig.DecisionLog log(TomlTable parsed) {
        // 日志开关只接受布尔值。
        if (!(parsed.get(List.of("log_decisions")) instanceof Boolean verbose)) {
            throw TOML.invalid("log_decisions 必须是布尔值");
        }
        if (verbose) {
            return HumanoidBrainConfig.DecisionLog.VERBOSE;
        }
        return HumanoidBrainConfig.DecisionLog.QUIET;
    }

    private static HumanoidBrainConfig.ThreatRules threat(TomlTable table) {
        // 记忆时长与各距离都必须为正。
        TOML.checkKeys(table, Set.of("memory_seconds", "ally_radius", "forget_distance", "safe_distance"), "threat.");
        return new HumanoidBrainConfig.ThreatRules(positive(table, "memory_seconds", "threat"),
                positive(table, "ally_radius", "threat"),
                positive(table, "forget_distance", "threat"),
                positive(table, "safe_distance", "threat"));
    }

    private static AssessmentRules assessment(TomlTable table, double safeDistance) {
        // 权重非负，门槛为 [0, 1] 比例；对敌余量可以为负。
        TOML.checkKeys(table, Set.of("realm_weight", "strike_weight", "armed_weight", "body_weight", "qi_weight",
                "casting_weight", "engage_margin", "retreat_hp_ratio", "retreat_part_hp_ratio",
                "arm_exhausted_ratio", "return_hp_ratio", "return_arm_ratio"), "assessment.");
        var weights = new AssessmentRules.Weights(nonNegative(table, "realm_weight", "assessment"),
                nonNegative(table, "strike_weight", "assessment"),
                nonNegative(table, "armed_weight", "assessment"),
                nonNegative(table, "body_weight", "assessment"),
                nonNegative(table, "qi_weight", "assessment"));
        var retreat = new AssessmentRules.Retreat(ratio(table, "retreat_hp_ratio", "assessment"),
                ratio(table, "retreat_part_hp_ratio", "assessment"),
                ratio(table, "arm_exhausted_ratio", "assessment"));
        var recovery = new AssessmentRules.Recovery(ratio(table, "return_hp_ratio", "assessment"),
                ratio(table, "return_arm_ratio", "assessment"), safeDistance);
        return new AssessmentRules(weights, nonNegative(table, "casting_weight", "assessment"),
                TOML.number(table, "engage_margin", "assessment.engage_margin"), retreat, recovery);
    }

    private static HumanoidBrainConfig.ResponseRules response(TomlTable table, double safeDistance) {
        // 毫秒为非负整数，剂量比为 [0, 1]，失败次数至少一次，站定距离为正。
        TOML.checkKeys(table, Set.of("reaction_window_millis", "interrupt_margin_millis", "absorb_own_hit_millis",
                "absorb_dose_ratio", "dodge_margin_millis", "assault_stand_off", "failure_limit",
                "failure_block_millis", CORNERED_STAND_DISTANCE), "response.");
        var reaction = new TacticRules.Reaction(millis(table, "reaction_window_millis"),
                millis(table, "interrupt_margin_millis"),
                millis(table, "absorb_own_hit_millis"),
                ratio(table, "absorb_dose_ratio", "response"),
                millis(table, "dodge_margin_millis"));
        var tactics = new TacticRules(reaction, safeDistance, corneredStandDistance(table));
        return new HumanoidBrainConfig.ResponseRules(tactics,
                positive(table, "assault_stand_off", "response"),
                TOML.integer(table, "failure_limit", "response.failure_limit"),
                millis(table, "failure_block_millis"));
    }

    private static double corneredStandDistance(TomlTable table) {
        // 这个键在旧导出文件里没有：缺失时读 JAR 默认资源的同名键，load 启动时另行提示。
        TomlTable source = table;
        if (!table.contains(CORNERED_STAND_DISTANCE)) {
            source = TOML.table(TOML.parse(TOML.resource(DEFAULT_RESOURCE)), "response", "response");
        }
        return positive(source, CORNERED_STAND_DISTANCE, "response");
    }

    private static VolumeTable volumes(TomlTable table) {
        // 七种内置类人必须全部出现；其他命名空间实体 ID 也接受，供另行注册的类人使用。
        var species = new HashMap<String, Map<HumanoidTier, List<VolumeTable.Grant>>>();
        for (String entityId : table.keySet()) {
            String path = "volumes." + entityId;
            requireId(entityId, path);
            species.put(entityId, tiers(TOML.table(table, entityId, path), path));
        }
        for (HumanoidSpecies builtIn : HumanoidSpecies.values()) {
            if (!species.containsKey(builtIn.entityId())) {
                throw TOML.invalid("学卷表缺少 " + builtIn.entityId());
            }
        }
        return new VolumeTable(species);
    }

    private static Map<HumanoidTier, List<VolumeTable.Grant>> tiers(TomlTable table, String path) {
        // 档位键为出生档位小写名（mortal、wandering_qi、true_qi），每档写全，不从低档继承；未写的档不学卷。
        var tiers = new EnumMap<HumanoidTier, List<VolumeTable.Grant>>(HumanoidTier.class);
        for (String key : table.keySet()) {
            HumanoidTier tier = HumanoidTier.fromId(key)
                    .orElseThrow(() -> TOML.invalid("未知档位 " + path + "." + key));
            if (!(table.get(List.of(key)) instanceof TomlArray grants)) {
                throw TOML.invalid(path + "." + key + " 必须是数组");
            }
            tiers.put(tier, grants(grants, path + "." + key));
        }
        return tiers;
    }

    private static List<VolumeTable.Grant> grants(TomlArray array, String path) {
        // 每项是 { volume, chance }，概率为 [0, 1]。
        var grants = new ArrayList<VolumeTable.Grant>();
        for (int index = 0; index < array.size(); index++) {
            if (!(array.get(index) instanceof TomlTable grant)) {
                throw TOML.invalid(path + " 的元素必须是表");
            }
            TOML.checkKeys(grant, Set.of("volume", "chance"), path + ".");
            if (!(grant.get(List.of("volume")) instanceof String volume)) {
                throw TOML.invalid(path + ".volume 必须是字符串");
            }
            requireId(volume, path + ".volume");
            grants.add(new VolumeTable.Grant(volume, ratio(grant, "chance", path)));
        }
        return List.copyOf(grants);
    }

    private static void requireId(String id, String path) {
        // 实体与卷 ID 都必须带命名空间。
        int separator = id.indexOf(':');
        if (separator <= 0 || separator == id.length() - 1) {
            throw TOML.invalid(path + " 必须是命名空间 ID");
        }
    }

    private static double ratio(TomlTable table, String key, String section) {
        // 比例与概率位于 [0, 1]。
        double value = TOML.number(table, key, section + "." + key);
        if (value < 0 || value > 1) {
            throw TOML.invalid(section + "." + key + " 必须位于 [0, 1]");
        }
        return value;
    }

    private static double nonNegative(TomlTable table, String key, String section) {
        // 权重不能为负。
        double value = TOML.number(table, key, section + "." + key);
        if (value < 0) {
            throw TOML.invalid(section + "." + key + " 不能为负");
        }
        return value;
    }

    private static double positive(TomlTable table, String key, String section) {
        // 距离与时长必须为正。
        double value = TOML.number(table, key, section + "." + key);
        if (value <= 0) {
            throw TOML.invalid(section + "." + key + " 必须为正");
        }
        return value;
    }

    private static long millis(TomlTable table, String key) {
        // 毫秒只接受非负整数，不通过 double 截断。
        if (!(table.get(List.of(key)) instanceof Long value) || value < 0) {
            throw TOML.invalid("response." + key + " 必须是非负整数");
        }
        return value;
    }
}
