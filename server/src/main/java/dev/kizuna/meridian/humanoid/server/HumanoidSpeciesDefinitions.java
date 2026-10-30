package dev.kizuna.meridian.humanoid.server;

import org.tomlj.TomlTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 物种表读取与登记：其他模组在初始化时交来一份 TOML，全部条目校验通过后才逐一登记。 */
public final class HumanoidSpeciesDefinitions {
    private static final HumanoidToml TOML = new HumanoidToml(HumanoidErrors.SPECIES);
    private static final Set<String> ENTRY_KEYS = Set.of("template", "default_tier", "death_rule", "brain",
            "profile");

    private HumanoidSpeciesDefinitions() {
        // 读取器不持有物种状态，登记结果保存在 HumanoidSpecies。
    }

    public static List<HumanoidSpecies> registerAll(String text) {
        // 先解析整张表并确认没有已登记或内置的实体 ID，避免半张表生效。
        List<HumanoidSpeciesDefinition> definitions = parse(text);
        for (HumanoidSpeciesDefinition definition : definitions) {
            if (HumanoidSpecies.fromEntityId(definition.entityId()).isPresent()) {
                throw TOML.invalid("物种已存在 " + definition.entityId());
            }
        }
        return definitions.stream().map(HumanoidSpecies::register).toList();
    }

    public static List<HumanoidSpeciesDefinition> parse(String text) {
        // 顶层只有 species 表，每个键是一个实体 ID。
        var parsed = TOML.parse(text);
        TOML.checkKeys(parsed, Set.of("species"), "");
        TomlTable species = TOML.table(parsed, "species", "species");
        var definitions = new ArrayList<HumanoidSpeciesDefinition>();
        for (String entityId : species.keySet()) {
            String path = "species." + entityId;
            definitions.add(definition(entityId, TOML.table(species, entityId, path), path));
        }
        return List.copyOf(definitions);
    }

    private static HumanoidSpeciesDefinition definition(String entityId, TomlTable table, String path) {
        // 模板、默认档位与画像必填；死亡规则缺省为人体规则，大脑缺省接管。
        TOML.checkKeys(table, ENTRY_KEYS, path + ".");
        String template = string(table, "template", path);
        HumanoidTier tier = HumanoidTier.fromId(string(table, "default_tier", path))
                .orElseThrow(() -> TOML.invalid(path + ".default_tier 必须是 mortal、wandering_qi 或 true_qi"));
        var profile = HumanoidConfigLoader.speciesProfile(TOML, TOML.table(table, "profile", path + ".profile"),
                path + ".profile");
        var control = new HumanoidSpeciesDefinition.Control(deathRule(table, path), brain(table, path));
        return new HumanoidSpeciesDefinition(entityId, template, tier, profile, control);
    }

    private static HumanoidDeathRule deathRule(TomlTable table, String path) {
        // 未写时按人体规则判死。
        if (!table.contains("death_rule")) {
            return HumanoidDeathRule.HUMAN;
        }
        return HumanoidDeathRule.fromId(string(table, "death_rule", path))
                .orElseThrow(() -> TOML.invalid(path + ".death_rule 必须是 human 或 never"));
    }

    private static HumanoidSpeciesDefinition.Brain brain(TomlTable table, String path) {
        // 未写时由类人大脑接管；写了就必须是布尔值。
        if (!table.contains("brain")) {
            return HumanoidSpeciesDefinition.Brain.MANAGED;
        }
        if (!(table.get(List.of("brain")) instanceof Boolean managed)) {
            throw TOML.invalid(path + ".brain 必须是布尔值");
        }
        if (managed) {
            return HumanoidSpeciesDefinition.Brain.MANAGED;
        }
        return HumanoidSpeciesDefinition.Brain.UNMANAGED;
    }

    private static String string(TomlTable table, String key, String path) {
        // 字符串字段缺失或类型不符都在加载时拒绝。
        if (!(table.get(List.of(key)) instanceof String value)) {
            throw TOML.invalid(path + "." + key + " 必须是字符串");
        }
        return value;
    }
}
