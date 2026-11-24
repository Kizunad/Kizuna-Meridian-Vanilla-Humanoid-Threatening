package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.config.TomlFile;
import org.tomlj.TomlTable;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 类人实体出生参数的 TOML 读取器；解析结果交给不可变配置构造器统一校验。 */
public final class HumanoidConfigLoader {
    public static final String DEFAULT_RESOURCE = "/meridian-humanoid.toml";
    private static final TomlFile TOML =
            new TomlFile(HumanoidErrors.CONFIG + " " + DEFAULT_RESOURCE);

    private HumanoidConfigLoader() {
        // 配置加载器不持有服务器实例或可变配置。
    }

    public static HumanoidConfig defaults() {
        // JAR 资源是默认值唯一来源，宿主可以把它复制到 config 目录后修改。
        return parse(TomlFile.resource(HumanoidConfigLoader.class, DEFAULT_RESOURCE));
    }

    public static HumanoidConfig load(Path path) {
        // 首次加载导出默认文件；已有文件只读取，不覆盖运营方调整。
        return parse(TomlFile.readOrExport(path, HumanoidConfigLoader.class, DEFAULT_RESOURCE));
    }

    public static HumanoidConfig parse(String text) {
        // TOML 解析和字段白名单在同一入口完成，拼错的参数不能静默使用默认值。
        var parsed = TOML.parse(text);
        TOML.checkKeys(parsed, Set.of("config_version", "lifespan_years", "age_min_years", "age_max_years",
                "opened_lumen_gain", "natural_fill_volume_ratio", "main_attribute_share_min",
                "main_attribute_share_max", "true_qi_reference_concentration", "true_qi_concentration_cap",
                "true_qi_no_region_factor", "fill", "species"), "");

        TomlTable fill = TOML.table(parsed, "fill", "fill");
        TOML.checkKeys(fill, Set.of("wandering_qi", "true_qi"), "fill.");
        HumanoidConfig.FillRange wandering = fillRange(TOML.table(fill, "wandering_qi", "fill.wandering_qi"),
                "fill.wandering_qi");
        HumanoidConfig.FillRange trueQi = fillRange(TOML.table(fill, "true_qi", "fill.true_qi"), "fill.true_qi");

        var profiles = speciesProfiles(TOML.table(parsed, "species", "species"));
        return new HumanoidConfig(
                TOML.positiveInteger(parsed, "config_version", "config_version"),
                TOML.finiteNumber(parsed, "lifespan_years", "lifespan_years"),
                TOML.finiteNumber(parsed, "age_min_years", "age_min_years"),
                TOML.finiteNumber(parsed, "age_max_years", "age_max_years"),
                TOML.finiteNumber(parsed, "opened_lumen_gain", "opened_lumen_gain"),
                TOML.finiteNumber(parsed, "natural_fill_volume_ratio", "natural_fill_volume_ratio"),
                TOML.finiteNumber(parsed, "main_attribute_share_min", "main_attribute_share_min"),
                TOML.finiteNumber(parsed, "main_attribute_share_max", "main_attribute_share_max"),
                TOML.finiteNumber(parsed, "true_qi_reference_concentration", "true_qi_reference_concentration"),
                TOML.finiteNumber(parsed, "true_qi_concentration_cap", "true_qi_concentration_cap"),
                TOML.finiteNumber(parsed, "true_qi_no_region_factor", "true_qi_no_region_factor"), wandering, trueQi,
                profiles);
    }

    private static Map<String, HumanoidConfig.SpeciesProfile> speciesProfiles(TomlTable species) {
        // 本文件只配置七种内置物种，按实体 ID 存放；登记物种的画像随其定义提供。
        var profiles = new LinkedHashMap<String, HumanoidConfig.SpeciesProfile>();
        for (String key : species.keySet()) {
            boolean builtIn = HumanoidSpecies.values().stream().anyMatch(value -> value.entityId().equals(key));
            if (!builtIn) {
                throw TOML.invalid("未知物种 " + key);
            }
            String path = "species." + key;
            profiles.put(key, speciesProfile(TOML, TOML.table(species, key, path), path));
        }
        if (profiles.size() != HumanoidSpecies.values().size()) {
            throw TOML.invalid("物种配置必须覆盖全部七种类人实体");
        }
        return profiles;
    }

    private static HumanoidConfig.FillRange fillRange(TomlTable table, String path) {
        // 每档的密度上限保持开区间语义，具体合法性由 FillRange 构造器确认。
        TOML.checkKeys(table, Set.of("lumen_min", "lumen_max", "wall_min", "wall_max", "density_min", "density_max",
                "free_residual_probability", "free_residual_volume_max"), path + ".");
        return new HumanoidConfig.FillRange(TOML.finiteNumber(table, "lumen_min", path + ".lumen_min"),
                TOML.finiteNumber(table, "lumen_max", path + ".lumen_max"),
                TOML.finiteNumber(table, "wall_min", path + ".wall_min"),
                TOML.finiteNumber(table, "wall_max", path + ".wall_max"),
                TOML.finiteNumber(table, "density_min", path + ".density_min"),
                TOML.finiteNumber(table, "density_max", path + ".density_max"),
                TOML.finiteNumber(table, "free_residual_probability", path + ".free_residual_probability"),
                TOML.finiteNumber(table, "free_residual_volume_max", path + ".free_residual_volume_max"));
    }

    static HumanoidConfig.SpeciesProfile speciesProfile(TomlFile toml, TomlTable table, String path) {
        // 物种偏向只改变随机权重，实体 ID 与结构仍由生成器固定；物种表读取器也用它解析登记物种的画像。
        toml.checkKeys(table, Set.of("true_qi_base_probability", "opening_count_multiplier", "fill_multiplier",
                "wall_fill_multiplier", "arm_opening_weight", "foot_opening_weight",
                "wandering_qi_leg_training_growth", "true_qi_leg_training_growth",
                "wandering_qi_arm_training_growth", "true_qi_arm_training_growth"), path + ".");
        return new HumanoidConfig.SpeciesProfile(
                toml.finiteNumber(table, "true_qi_base_probability", path + ".true_qi_base_probability"),
                toml.finiteNumber(table, "opening_count_multiplier", path + ".opening_count_multiplier"),
                toml.finiteNumber(table, "fill_multiplier", path + ".fill_multiplier"),
                toml.finiteNumber(table, "wall_fill_multiplier", path + ".wall_fill_multiplier"),
                toml.finiteNumber(table, "arm_opening_weight", path + ".arm_opening_weight"),
                toml.finiteNumber(table, "foot_opening_weight", path + ".foot_opening_weight"),
                optionalNumber(toml, table, "wandering_qi_leg_training_growth", path),
                optionalNumber(toml, table, "true_qi_leg_training_growth", path),
                optionalNumber(toml, table, "wandering_qi_arm_training_growth", path),
                optionalNumber(toml, table, "true_qi_arm_training_growth", path));
    }

    private static double optionalNumber(TomlFile toml, TomlTable table, String key, String path) {
        // 登记物种可以省略成长配置，省略时保持没有出生训练成长。
        if (!table.contains(key)) {
            return 0;
        }
        return toml.finiteNumber(table, key, path + "." + key);
    }
}
