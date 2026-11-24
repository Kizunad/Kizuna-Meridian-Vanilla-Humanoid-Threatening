package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.config.TomlFile;
import org.tomlj.TomlTable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 读 [threat.score]：默认表每键必填，物种表只写要覆盖的键；扣分项不为正，其余不为负。 */
final class ThreatScoringLoader {
    static final String SCORE = "score";
    private static final String SPECIES = "species";
    private static final String PATH = "threat." + SCORE;
    private static final List<String> KEYS = List.of("distance", "low_health", "low_health_absolute",
            "recent_damage", "targeting_me", "danger", "summon", "unreachable", "stickiness",
            "switch_margin", "min_hold_seconds");
    private static final Set<String> PENALTIES = Set.of("summon", "unreachable");

    private ThreatScoringLoader() {
        // 只有静态读取入口。
    }

    static ThreatScoring parse(TomlFile toml, TomlTable table) {
        // 默认表逐键必填；物种表按实体 ID，缺的键继承默认表。
        var allowed = new HashSet<>(KEYS);
        allowed.add(SPECIES);
        toml.checkKeys(table, allowed, PATH + ".");
        Map<String, Double> defaults = values(toml, table, Map.of(), PATH);
        var species = new HashMap<String, ThreatScoring.Weights>();
        if (table.get(List.of(SPECIES)) != null) {
            String speciesPath = PATH + "." + SPECIES;
            TomlTable overrides = toml.table(table, SPECIES, speciesPath);
            for (String entityId : overrides.keySet()) {
                String path = speciesPath + "." + entityId;
                HumanoidBrainConfigLoader.requireId(entityId, path);
                TomlTable entry = toml.table(overrides, entityId, path);
                toml.checkKeys(entry, Set.copyOf(KEYS), path + ".");
                species.put(entityId, weights(values(toml, entry, defaults, path)));
            }
        }
        return new ThreatScoring(weights(defaults), species);
    }

    private static Map<String, Double> values(TomlFile toml, TomlTable table, Map<String, Double> inherited,
                                              String path) {
        // 表里写了的键校验后取值，没写的取继承值；默认表没有继承值，缺键即报错。
        var values = new HashMap<String, Double>();
        for (String key : KEYS) {
            if (table.get(List.of(key)) == null && inherited.containsKey(key)) {
                values.put(key, inherited.get(key));
                continue;
            }
            double value = toml.finiteNumber(table, key, path + "." + key);
            if (PENALTIES.contains(key) && value > 0) {
                throw toml.invalid(path + "." + key + " 是扣分项，不能为正");
            }
            if (!PENALTIES.contains(key) && value < 0) {
                throw toml.invalid(path + "." + key + " 不能为负");
            }
            values.put(key, value);
        }
        return values;
    }

    private static ThreatScoring.Weights weights(Map<String, Double> values) {
        // 键名与记录分量一一对应。
        return new ThreatScoring.Weights(values.get("distance"), values.get("low_health"),
                values.get("low_health_absolute"), values.get("recent_damage"), values.get("targeting_me"),
                values.get("danger"), values.get("summon"), values.get("unreachable"), values.get("stickiness"),
                values.get("switch_margin"), values.get("min_hold_seconds"));
    }
}
