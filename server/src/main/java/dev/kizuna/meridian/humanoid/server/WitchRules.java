package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.config.TomlFile;
import org.tomlj.TomlTable;

import java.nio.file.Path;
import java.util.Set;

/** 女巫选药的不可变 TOML 规则，所有阈值和距离在启动时一次解析。 */
public record WitchRules(Quality quality, Healing healing, Range range, Charm charm,
                         Environment environment, Aim aim, Sound sound, Vanilla vanilla) {
    private static final String DEFAULT_RESOURCE = "/meridian-witch-ai.toml";
    private static final TomlFile TOML =
            new TomlFile("女巫 AI 配置无效" + " " + DEFAULT_RESOURCE);

    public static WitchRules load(Path path) {
        // 首次加载导出默认文件；已有文件只读取，不覆盖运营方调整。
        return parse(TomlFile.readOrExport(path, WitchRules.class, DEFAULT_RESOURCE));
    }

    static WitchRules parse(String text) {
        // 顶层和每个分组都检查白名单，拼错字段在启动时直接拒绝。
        TomlTable parsed = TOML.parse(text);
        TOML.checkKeys(parsed, Set.of("quality", "healing", "range", "charm", "environment", "aim", "sound",
                "vanilla"), "");
        Quality quality = quality(TOML.table(parsed, "quality", "quality"));
        Healing healing = healing(TOML.table(parsed, "healing", "healing"));
        Range range = range(TOML.table(parsed, "range", "range"));
        Charm charm = charm(TOML.table(parsed, "charm", "charm"));
        Environment environment = environment(TOML.table(parsed, "environment", "environment"));
        Aim aim = aim(TOML.table(parsed, "aim", "aim"));
        Sound sound = sound(TOML.table(parsed, "sound", "sound"));
        Vanilla vanilla = vanilla(TOML.table(parsed, "vanilla", "vanilla"));
        return new WitchRules(quality, healing, range, charm, environment, aim, sound, vanilla);
    }

    private static Quality quality(TomlTable table) {
        TOML.checkKeys(table, Set.of("wandering", "true_qi"), "quality.");
        return new Quality(TOML.positiveInteger(table, "wandering", "quality.wandering"),
                TOML.positiveInteger(table, "true_qi", "quality.true_qi"));
    }

    private static Healing healing(TomlTable table) {
        TOML.checkKeys(table, Set.of("anoint_hp_ratio", "imbibe_hp_ratio", "ally_hp_ratio"), "healing.");
        return new Healing(ratio(table, "anoint_hp_ratio", "healing"),
                ratio(table, "imbibe_hp_ratio", "healing"), ratio(table, "ally_hp_ratio", "healing"));
    }

    private static Range range(TomlTable table) {
        TOML.checkKeys(table, Set.of("enemy_radius", "ally_radius", "ally_enemy_clearance", "throw_min_distance",
                "throw_max_distance", "fan_distance", "trigger_distance"), "range.");
        return new Range(positive(table, "enemy_radius", "range"), positive(table, "ally_radius", "range"),
                positive(table, "ally_enemy_clearance", "range"), positive(table, "throw_min_distance", "range"),
                positive(table, "throw_max_distance", "range"), positive(table, "fan_distance", "range"),
                positive(table, "trigger_distance", "range"));
    }

    private static Charm charm(TomlTable table) {
        TOML.checkKeys(table, Set.of("residue_trigger_dose", "close_distance", "crowd_radius", "crowd_count",
                "brain_ceiling", "direct_cooldown_ticks"), "charm.");
        return new Charm(ratio(table, "residue_trigger_dose", "charm"), positive(table, "close_distance", "charm"),
                positive(table, "crowd_radius", "charm"),
                TOML.positiveInteger(table, "crowd_count", "charm.crowd_count"),
                nonNegative(table, "brain_ceiling", "charm"),
                TOML.positiveInteger(table, "direct_cooldown_ticks", "charm.direct_cooldown_ticks"));
    }

    private static Environment environment(TomlTable table) {
        TOML.checkKeys(table, Set.of("running_speed_per_tick", "dark_light_ceiling", "insect_radius",
                "heavy_armor_defense"), "environment.");
        return new Environment(positive(table, "running_speed_per_tick", "environment"),
                TOML.positiveInteger(table, "dark_light_ceiling", "environment.dark_light_ceiling"),
                positive(table, "insect_radius", "environment"),
                nonNegative(table, "heavy_armor_defense", "environment"));
    }

    private static Aim aim(TomlTable table) {
        TOML.checkKeys(table, Set.of("head_height", "torso_height", "legs_height"), "aim.");
        return new Aim(ratio(table, "head_height", "aim"), ratio(table, "torso_height", "aim"),
                ratio(table, "legs_height", "aim"));
    }

    private static Sound sound(TomlTable table) {
        TOML.checkKeys(table, Set.of("celebrate_delay_ticks", "volume", "pitch"), "sound.");
        return new Sound(TOML.positiveInteger(table, "celebrate_delay_ticks", "sound.celebrate_delay_ticks"),
                nonNegative(table, "volume", "sound"), positive(table, "pitch", "sound"));
    }

    private static Vanilla vanilla(TomlTable table) {
        TOML.checkKeys(table, Set.of("short_distance", "short_speed", "long_speed", "scatter", "lob_height",
                "target_eye_offset"), "vanilla.");
        return new Vanilla(positive(table, "short_distance", "vanilla"), positive(table, "short_speed", "vanilla"),
                positive(table, "long_speed", "vanilla"), nonNegative(table, "scatter", "vanilla"),
                nonNegative(table, "lob_height", "vanilla"), positive(table, "target_eye_offset", "vanilla"));
    }

    private static double ratio(TomlTable table, String key, String section) {
        double value = TOML.finiteNumber(table, key, section + "." + key);
        if (value < 0 || value > 1) {
            throw TOML.invalid(section + "." + key + " 必须位于 [0, 1]");
        }
        return value;
    }

    private static double positive(TomlTable table, String key, String section) {
        double value = TOML.finiteNumber(table, key, section + "." + key);
        if (value <= 0) {
            throw TOML.invalid(section + "." + key + " 必须为正");
        }
        return value;
    }

    private static double nonNegative(TomlTable table, String key, String section) {
        double value = TOML.finiteNumber(table, key, section + "." + key);
        if (value < 0) {
            throw TOML.invalid(section + "." + key + " 不能为负");
        }
        return value;
    }

    public record Quality(int wandering, int trueQi) { }

    public record Healing(double anointHpRatio, double imbibeHpRatio, double allyHpRatio) { }

    public record Range(double enemyRadius, double allyRadius, double allyEnemyClearance,
                        double throwMinDistance, double throwMaxDistance, double fanDistance,
                        double triggerDistance) { }

    public record Charm(double residueTriggerDose, double closeDistance, double crowdRadius, int crowdCount,
                        double brainCeiling, int directCooldownTicks) { }

    public record Environment(double runningSpeedPerTick, int darkLightCeiling, double insectRadius,
                              double heavyArmorDefense) { }

    public record Aim(double headHeight, double torsoHeight, double legsHeight) { }

    public record Sound(int celebrateDelayTicks, double volume, double pitch) { }

    public record Vanilla(double shortDistance, double shortSpeed, double longSpeed, double scatter,
                          double lobHeight, double targetEyeOffset) { }
}
