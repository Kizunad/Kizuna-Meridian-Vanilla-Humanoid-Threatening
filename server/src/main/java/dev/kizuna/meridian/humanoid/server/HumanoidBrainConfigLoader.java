package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.core.Realm;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 类人大脑 TOML 读取器：字段白名单逐层检查，范围非法或缺物种时在加载时拒绝。 */
public final class HumanoidBrainConfigLoader {
    public static final String DEFAULT_RESOURCE = "/meridian-humanoid-brain.toml";
    private static final int CONFIG_VERSION = 1;
    private static final HumanoidToml TOML = new HumanoidToml(HumanoidErrors.BRAIN);

    private HumanoidBrainConfigLoader() {
        // 配置加载器不持有服务器实例或可变配置。
    }

    public static HumanoidBrainConfig defaults() {
        // JAR 资源是默认值唯一来源。
        return parse(TOML.resource(DEFAULT_RESOURCE));
    }

    public static HumanoidBrainConfig load(Path path) {
        // 首次加载导出默认文件；已有文件只读取，不覆盖运营方调整。
        return parse(TOML.readOrExport(path, DEFAULT_RESOURCE));
    }

    public static HumanoidBrainConfig parse(String text) {
        // 版本不符时明确报错，提示删除或合并旧文件，不做静默迁移。
        var parsed = TOML.parse(text);
        TOML.checkKeys(parsed, Set.of("config_version", "decision_interval_ticks", "log_decisions", "threat",
                "assessment", "response", "volumes"), "");
        if (TOML.integer(parsed, "config_version", "config_version") != CONFIG_VERSION) {
            throw TOML.invalid("config_version 必须为 " + CONFIG_VERSION
                    + "，请删除旧文件或按新默认值合并");
        }
        var schedule = new HumanoidBrainConfig.Schedule(
                TOML.integer(parsed, "decision_interval_ticks", "decision_interval_ticks"), log(parsed));
        var threat = threat(TOML.table(parsed, "threat", "threat"));
        var assessment = assessment(TOML.table(parsed, "assessment", "assessment"), threat.safeDistance());
        var response = response(TOML.table(parsed, "response", "response"), threat.safeDistance());
        var volumes = volumes(TOML.table(parsed, "volumes", "volumes"));
        return new HumanoidBrainConfig(schedule, threat, assessment, response, volumes);
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
        // 毫秒为非负整数，剂量比为 [0, 1]，失败次数至少一次。
        TOML.checkKeys(table, Set.of("reaction_window_millis", "interrupt_margin_millis", "absorb_own_hit_millis",
                "absorb_dose_ratio", "dodge_margin_millis", "assault_stand_off", "failure_limit",
                "failure_block_millis"), "response.");
        var reaction = new TacticRules.Reaction(millis(table, "reaction_window_millis"),
                millis(table, "interrupt_margin_millis"),
                millis(table, "absorb_own_hit_millis"),
                ratio(table, "absorb_dose_ratio", "response"),
                millis(table, "dodge_margin_millis"));
        return new HumanoidBrainConfig.ResponseRules(new TacticRules(reaction, safeDistance),
                positive(table, "assault_stand_off", "response"),
                TOML.integer(table, "failure_limit", "response.failure_limit"),
                millis(table, "failure_block_millis"));
    }

    private static VolumeTable volumes(TomlTable table) {
        // 七种内置类人必须全部出现；其他命名空间实体 ID 也接受，供另行注册的类人使用。
        var species = new HashMap<String, Map<Realm, List<VolumeTable.Grant>>>();
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

    private static Map<Realm, List<VolumeTable.Grant>> tiers(TomlTable table, String path) {
        // 档位键为境界名小写，每档写全，不从低档继承。
        var tiers = new EnumMap<Realm, List<VolumeTable.Grant>>(Realm.class);
        for (String key : table.keySet()) {
            Realm realm = realm(key, path);
            if (!(table.get(List.of(key)) instanceof TomlArray grants)) {
                throw TOML.invalid(path + "." + key + " 必须是数组");
            }
            tiers.put(realm, grants(grants, path + "." + key));
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

    private static Realm realm(String key, String path) {
        // 只接受已定义的境界名。
        try {
            return Realm.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw TOML.invalid("未知境界 " + path + "." + key);
        }
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
