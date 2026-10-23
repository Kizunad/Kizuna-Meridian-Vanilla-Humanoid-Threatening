package dev.kizuna.meridian.humanoid.server;

import org.tomlj.Toml;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 类人实体出生参数的 TOML 读取器；解析结果交给不可变配置构造器统一校验。 */
public final class HumanoidConfigLoader {
    public static final String DEFAULT_RESOURCE = "/meridian-humanoid.toml";

    private HumanoidConfigLoader() {
        // 配置加载器不持有服务器实例或可变配置。
    }

    public static HumanoidConfig defaults() {
        // JAR 资源是默认值唯一来源，宿主可以把它复制到 config 目录后修改。
        try (InputStream input = HumanoidConfigLoader.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (input == null) {
                throw HumanoidErrors.invalid(HumanoidErrors.CONFIG + ": 缺少默认资源");
            }
            return parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw HumanoidErrors.invalid(HumanoidErrors.CONFIG + ": 无法读取默认资源");
        }
    }

    public static HumanoidConfig load(Path path) {
        // 首次加载导出默认文件；已有文件只读取，不覆盖运营方调整。
        if (path == null) {
            throw HumanoidErrors.invalid(HumanoidErrors.CONFIG + ": 配置路径不能为空");
        }
        try {
            if (!Files.exists(path)) {
                Path parent = path.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (InputStream input = HumanoidConfigLoader.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                    if (input == null) {
                        throw HumanoidErrors.invalid(HumanoidErrors.CONFIG + ": 缺少默认资源");
                    }
                    Files.copy(input, path);
                }
            }
            return parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw HumanoidErrors.invalid(HumanoidErrors.CONFIG + ": 无法读取配置文件");
        }
    }

    public static HumanoidConfig parse(String text) {
        // TOML 解析和字段白名单在同一入口完成，拼错的参数不能静默使用默认值。
        if (text == null || text.isBlank()) {
            throw HumanoidErrors.invalid(HumanoidErrors.CONFIG + ": 配置文本不能为空");
        }
        var parsed = Toml.parse(text);
        if (parsed.hasErrors()) {
            throw HumanoidErrors.invalid(HumanoidErrors.CONFIG + ": TOML 解析失败");
        }
        checkKeys(parsed, Set.of("config_version", "lifespan_years", "age_min_years", "age_max_years",
                "opened_lumen_gain", "natural_fill_volume_ratio", "main_attribute_share_min",
                "main_attribute_share_max", "true_qi_reference_concentration", "true_qi_concentration_cap",
                "true_qi_no_region_factor", "fill", "species", "particle"), "");

        TomlTable fill = table(parsed, "fill", "fill");
        checkKeys(fill, Set.of("wandering_qi", "true_qi"), "fill.");
        HumanoidConfig.FillRange wandering = fillRange(table(fill, "wandering_qi", "fill.wandering_qi"),
                "fill.wandering_qi");
        HumanoidConfig.FillRange trueQi = fillRange(table(fill, "true_qi", "fill.true_qi"), "fill.true_qi");

        TomlTable species = table(parsed, "species", "species");
        var profiles = new EnumMap<HumanoidSpecies, HumanoidConfig.SpeciesProfile>(HumanoidSpecies.class);
        var knownKeys = new HashSet<String>();
        for (String key : species.keySet()) {
            HumanoidSpecies value = HumanoidSpecies.fromEntityId(key)
                    .orElseThrow(() -> HumanoidErrors.invalid(HumanoidErrors.CONFIG + ": 未知物种 " + key));
            knownKeys.add(key);
            profiles.put(value, speciesProfile(table(species, key, "species." + key), "species." + key));
        }
        if (knownKeys.size() != HumanoidSpecies.values().length) {
            throw HumanoidErrors.invalid(HumanoidErrors.CONFIG + ": 物种配置必须覆盖本批次全部七种类人实体");
        }

        TomlTable particle = table(parsed, "particle", "particle");
        checkKeys(particle, Set.of("enabled", "interval_ticks", "colors"), "particle.");
        TomlTable colors = table(particle, "colors", "particle.colors");
        var particleColors = new java.util.HashMap<String, Integer>();
        for (String key : colors.keySet()) {
            Object value = colors.get(List.of(key));
            if (!(value instanceof Long color) || color < 0 || color > 0xFFFFFF) {
                throw HumanoidErrors.invalid(HumanoidErrors.CONFIG + ": particle.colors." + key
                        + " 必须是 24 位 RGB 整数");
            }
            particleColors.put(key, color.intValue());
        }
        return new HumanoidConfig(
                integer(parsed, "config_version", "config_version"),
                number(parsed, "lifespan_years", "lifespan_years"),
                number(parsed, "age_min_years", "age_min_years"),
                number(parsed, "age_max_years", "age_max_years"),
                number(parsed, "opened_lumen_gain", "opened_lumen_gain"),
                number(parsed, "natural_fill_volume_ratio", "natural_fill_volume_ratio"),
                number(parsed, "main_attribute_share_min", "main_attribute_share_min"),
                number(parsed, "main_attribute_share_max", "main_attribute_share_max"),
                number(parsed, "true_qi_reference_concentration", "true_qi_reference_concentration"),
                number(parsed, "true_qi_concentration_cap", "true_qi_concentration_cap"),
                number(parsed, "true_qi_no_region_factor", "true_qi_no_region_factor"), wandering, trueQi,
                profiles, bool(particle, "enabled", "particle.enabled"),
                integer(particle, "interval_ticks", "particle.interval_ticks"), particleColors);
    }

    private static HumanoidConfig.FillRange fillRange(TomlTable table, String path) {
        // 每档的密度上限保持开区间语义，具体合法性由 FillRange 构造器确认。
        checkKeys(table, Set.of("lumen_min", "lumen_max", "wall_min", "wall_max", "density_min", "density_max",
                "free_residual_probability", "free_residual_volume_max"), path + ".");
        return new HumanoidConfig.FillRange(number(table, "lumen_min", path + ".lumen_min"),
                number(table, "lumen_max", path + ".lumen_max"),
                number(table, "wall_min", path + ".wall_min"),
                number(table, "wall_max", path + ".wall_max"),
                number(table, "density_min", path + ".density_min"),
                number(table, "density_max", path + ".density_max"),
                number(table, "free_residual_probability", path + ".free_residual_probability"),
                number(table, "free_residual_volume_max", path + ".free_residual_volume_max"));
    }

    private static HumanoidConfig.SpeciesProfile speciesProfile(TomlTable table, String path) {
        // 物种偏向只改变随机权重，实体 ID 与结构仍由生成器固定。
        checkKeys(table, Set.of("true_qi_base_probability", "opening_count_multiplier", "fill_multiplier",
                "wall_fill_multiplier", "arm_opening_weight", "foot_opening_weight"), path + ".");
        return new HumanoidConfig.SpeciesProfile(
                number(table, "true_qi_base_probability", path + ".true_qi_base_probability"),
                number(table, "opening_count_multiplier", path + ".opening_count_multiplier"),
                number(table, "fill_multiplier", path + ".fill_multiplier"),
                number(table, "wall_fill_multiplier", path + ".wall_fill_multiplier"),
                number(table, "arm_opening_weight", path + ".arm_opening_weight"),
                number(table, "foot_opening_weight", path + ".foot_opening_weight"));
    }

    private static TomlTable table(TomlTable parent, String key, String path) {
        // 缺少章节和标量冒充章节都视为配置错误，避免产生半套规则。
        Object value = parent.get(List.of(key));
        if (!(value instanceof TomlTable result)) {
            throw HumanoidErrors.invalid(HumanoidErrors.CONFIG + ": 缺少或错误的表 " + path);
        }
        return result;
    }

    private static int integer(TomlTable table, String key, String path) {
        // 版本和 tick 间隔只接受完整正整数，不通过 double 截断。
        Object value = table.get(List.of(key));
        if (!(value instanceof Long integer) || integer <= 0 || integer > Integer.MAX_VALUE) {
            throw HumanoidErrors.invalid(HumanoidErrors.CONFIG + ": " + path + " 必须是正整数");
        }
        return integer.intValue();
    }

    private static double number(TomlTable table, String key, String path) {
        // TOML 整数和小数统一为有限 double，范围由配置 record 继续校验。
        Object value = table.get(List.of(key));
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw HumanoidErrors.invalid(HumanoidErrors.CONFIG + ": " + path + " 必须是有限数值");
        }
        return number.doubleValue();
    }

    private static boolean bool(TomlTable table, String key, String path) {
        // 开关必须是真正的 TOML 布尔值，不接受字符串猜测。
        Object value = table.get(List.of(key));
        if (!(value instanceof Boolean result)) {
            throw HumanoidErrors.invalid(HumanoidErrors.CONFIG + ": " + path + " 必须是布尔值");
        }
        return result;
    }

    private static void checkKeys(TomlTable table, Set<String> allowed, String prefix) {
        // 每层独立检查，错误路径直接包含章节名，便于定位运维配置拼写错误。
        for (String key : table.keySet()) {
            if (!allowed.contains(key)) {
                throw HumanoidErrors.invalid(HumanoidErrors.CONFIG + ": 未知字段 " + prefix + key);
            }
        }
    }
}
