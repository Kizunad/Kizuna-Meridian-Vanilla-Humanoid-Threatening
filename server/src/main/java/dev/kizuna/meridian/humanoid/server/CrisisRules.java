package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.config.TomlFile;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 修为招敌的配置，启动时一次解析成不可变规则。
 * 第一次启动导出默认文件；运营文件缺的键按 JAR 默认值运行并提示，默认文件里没有的键拒绝启动。
 */
public record CrisisRules(boolean enabled, String genericParty, Frequency frequency, Omen omen, Heat heat,
                          Strength strength, Arrival arrival, Outcome outcome) {
    /** 招敌的一「天」：玩家在线满这么多 tick。 */
    public static final long DAY_TICKS = 24000;
    private static final String DEFAULT_RESOURCE = "/meridian-humanoid-crisis.toml";
    private static final TomlFile TOML =
            new TomlFile(HumanoidErrors.CRISIS + " " + DEFAULT_RESOURCE);
    private static final Logger LOGGER = System.getLogger("meridian_humanoid_crisis_config");

    private static TomlTable defaultsTable() {
        // 旧配置缺项时，从同一份随包资源读取对应规则。
        return TOML.parse(TomlFile.resource(CrisisRules.class, DEFAULT_RESOURCE));
    }

    public static CrisisRules defaults() {
        // JAR 资源是默认值唯一来源。
        return parse(TomlFile.resource(CrisisRules.class, DEFAULT_RESOURCE));
    }

    public static CrisisRules load(Path path) {
        // 已有文件缺的键启动时提示一次，程序不改运营方的文件。
        boolean existing = path != null && Files.exists(path);
        String text = TomlFile.readOrExport(path, CrisisRules.class, DEFAULT_RESOURCE);
        CrisisRules rules = parse(text);
        List<String> missing = missingDefaultKeys(text);
        if (existing && !missing.isEmpty()) {
            LOGGER.log(Level.WARNING, "招敌配置 " + path + " 缺少默认键 " + missing
                    + "；这些键按默认值运行，请照默认文件补入，程序不会自动修改文件");
        }
        return rules;
    }

    static List<String> missingDefaultKeys(String text) {
        // 默认资源里有、给定文本里缺的键，按键路径排序。
        TomlTable configured = TOML.parse(text);
        return defaultsTable().keyPathSet().stream()
                .filter(keyPath -> !configured.contains(keyPath))
                .map(Toml::joinKeyPath)
                .sorted()
                .toList();
    }

    public static CrisisRules parse(String text) {
        // 文本里有的键用文本的值，缺的用默认资源的值；默认资源里没有的键一律拒绝。
        Values values = new Values(TOML.parse(text), defaultsTable());
        values.rejectUnknown();
        String party = values.text("generic_party");
        requireId(party, "generic_party");
        return new CrisisRules(values.bool("enabled"), party, frequency(values), omen(values), heat(values),
                strength(values), arrival(values), outcome(values));
    }

    static void requireId(String id, String path) {
        // 队伍表和卷的 ID 都要带命名空间，如 meridian_humanoid:feud。
        if (!namespaced(id)) {
            throw TOML.invalid(path + " 必须是命名空间 ID");
        }
    }

    static boolean namespaced(String id) {
        // 冒号前后都不能空。
        int separator = id.indexOf(':');
        return separator > 0 && separator < id.length() - 1;
    }

    private static Frequency frequency(Values values) {
        // 天数可以为 0，表示不设这道限制。
        return new Frequency(values.count("frequency.min_interval_days", 0),
                values.count("frequency.protection_days", 0),
                values.count("frequency.wandering_ready_meridians", 0));
    }

    private static Omen omen(Values values) {
        // 征兆期至少一天、至少一次征兆；次数是闭区间 [最少, 最多]。
        List<Integer> count = values.counts("omen.count", 1);
        if (count.size() != 2 || count.get(0) > count.get(1)) {
            throw TOML.invalid("omen.count 必须是 [最少, 最多] 两个正整数");
        }
        return new Omen(values.count("omen.days", 1), count.get(0), count.get(1),
                values.positive("omen.rumor_radius"), values.positive("omen.note_bed_radius"));
    }

    private static Heat heat(Values values) {
        // 过线值为正；了结后留下的成数在 [0, 1]。
        return new Heat(values.positive("heat.threshold"), values.ratio("heat.kept_after_win"),
                values.ratio("heat.kept_after_escape"), rise(values),
                new Decay(values.count("heat.decay.after_days", 0), values.nonNegative("heat.decay.per_day")));
    }

    private static Rise rise(Values values) {
        // 开脉节点从小到大、不重复；稀有卷的概率线在 (0, 1]。
        List<Integer> nodes = values.counts("heat.rise.meridian_nodes", 1);
        for (int index = 1; index < nodes.size(); index++) {
            if (nodes.get(index) <= nodes.get(index - 1)) {
                throw TOML.invalid("heat.rise.meridian_nodes 必须从小到大且不重复");
            }
        }
        double rareChance = values.ratio("heat.rise.rare_chance");
        if (rareChance == 0) {
            throw TOML.invalid("heat.rise.rare_chance 必须大于 0");
        }
        return new Rise(values.nonNegative("heat.rise.true_qi_breakthrough"),
                values.nonNegative("heat.rise.meridian_node"), nodes,
                values.nonNegative("heat.rise.witnessed_kill"), values.positive("heat.rise.witness_radius"),
                values.nonNegative("heat.rise.rare_volume"), rareChance);
    }

    private static Strength strength(Values values) {
        // 一场至少来一个头目。
        return new Strength(values.count("strength.max_party", 1), values.count("strength.true_many_meridians", 1));
    }

    private static Arrival arrival(Values values) {
        // 现身距离是闭区间，近端不能大于远端。
        double min = values.positive("arrival.min_distance");
        double max = values.positive("arrival.max_distance");
        if (min > max) {
            throw TOML.invalid("arrival.min_distance 不能大于 arrival.max_distance");
        }
        return new Arrival(min, max, values.count("arrival.retry_seconds", 1));
    }

    private static Outcome outcome(Values values) {
        // 代价档只认三个小写名。
        String penalty = values.text("outcome.loss_penalty");
        LossPenalty lossPenalty = Arrays.stream(LossPenalty.values())
                .filter(candidate -> candidate.id().equals(penalty))
                .findFirst()
                .orElseThrow(() -> TOML.invalid("outcome.loss_penalty 只能是 light、medium、heavy"));
        if (lossPenalty == LossPenalty.HEAVY) {
            throw TOML.invalid("outcome.loss_penalty 暂时不能用 heavy：经脉伤了还没有恢复的规矩，伤了就再也好不了");
        }
        return new Outcome(lossPenalty, values.positive("outcome.escape_distance"),
                values.count("outcome.escape_unseen_seconds", 1), values.count("outcome.escape_time_limit_days", 1));
    }

    /** 两张表叠着读：运营文本优先，缺键退回默认资源；取值时就核对类型和范围。 */
    private static final class Values {
        private final TomlTable configured;
        private final TomlTable defaults;

        Values(TomlTable configured, TomlTable defaults) {
            // 两张表在解析入口各解析一次。
            this.configured = configured;
            this.defaults = defaults;
        }

        void rejectUnknown() {
            // 白名单就是默认资源的全部键路径；把标量写在默认是表的位置上同样拒绝。
            for (List<String> keyPath : configured.keyPathSet()) {
                if (!defaults.contains(keyPath) || defaults.isTable(keyPath)) {
                    throw TOML.invalid("未知字段 " + Toml.joinKeyPath(keyPath));
                }
            }
        }

        Object get(String path) {
            // 运营值优先，缺键用默认资源的值。
            if (configured.contains(path)) {
                return configured.get(path);
            }
            return defaults.get(path);
        }

        boolean bool(String path) {
            // 只接受 true 或 false。
            if (get(path) instanceof Boolean value) {
                return value;
            }
            throw TOML.invalid(path + " 必须是 true 或 false");
        }

        String text(String path) {
            // 只接受非空字符串。
            if (get(path) instanceof String value && !value.isBlank()) {
                return value;
            }
            throw TOML.invalid(path + " 必须是非空字符串");
        }

        int count(String path, int min) {
            // 只接受完整整数，不从小数截断。
            if (get(path) instanceof Long value && value >= min && value <= Integer.MAX_VALUE) {
                return value.intValue();
            }
            throw TOML.invalid(path + " 必须是不小于 " + min + " 的整数");
        }

        List<Integer> counts(String path, int min) {
            // 整数数组，每一项都不小于 min，数组不能为空。
            if (!(get(path) instanceof TomlArray array) || array.isEmpty()) {
                throw TOML.invalid(path + " 必须是非空整数数组");
            }
            List<Integer> result = new ArrayList<>();
            for (Object item : array.toList()) {
                if (!(item instanceof Long value) || value < min || value > Integer.MAX_VALUE) {
                    throw TOML.invalid(path + " 每一项都必须是不小于 " + min + " 的整数");
                }
                result.add(value.intValue());
            }
            return List.copyOf(result);
        }

        double nonNegative(String path) {
            // 有限且不为负。
            if (get(path) instanceof Number value && Double.isFinite(value.doubleValue()) && value.doubleValue() >= 0) {
                return value.doubleValue();
            }
            throw TOML.invalid(path + " 必须是不为负的有限数值");
        }

        double positive(String path) {
            // 有限且为正。
            double value = nonNegative(path);
            if (value == 0) {
                throw TOML.invalid(path + " 必须为正");
            }
            return value;
        }

        double ratio(String path) {
            // 位于 [0, 1]。
            double value = nonNegative(path);
            if (value > 1) {
                throw TOML.invalid(path + " 必须位于 [0, 1]");
            }
            return value;
        }
    }

    public enum LossPenalty {
        LIGHT, MEDIUM, HEAVY;

        public String id() {
            // 配置里写小写名。
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 两场的最短间隔、开局保护期，以及游炁玩家几条脉以后才会被风声招来。 */
    public record Frequency(int minIntervalDays, int protectionDays, int wanderingReadyMeridians) { }

    /** 征兆期几天、给几次，传言和字条的距离。 */
    public record Omen(int days, int minCount, int maxCount, double rumorRadius, double noteBedRadius) { }

    /** 风声过线值、了结后留几成，以及涨和落的规则。 */
    public record Heat(double threshold, double keptAfterWin, double keptAfterEscape, Rise rise, Decay decay) { }

    /** 风声各项涨多少；开脉节点从小到大。 */
    public record Rise(double trueQiBreakthrough, double meridianNode, List<Integer> meridianNodes,
                       double witnessedKill, double witnessRadius, double rareVolume, double rareChance) { }

    /** 多少天没涨以后开始落，之后每天落多少。 */
    public record Decay(int afterDays, double perDay) { }

    /** 一场最多几个人，真炁玩家几条脉以后算开脉多。 */
    public record Strength(int maxParty, int trueManyMeridians) { }

    /** 来人现身的距离区间和到点不合适时的重试间隔。 */
    public record Arrival(double minDistance, double maxDistance, int retrySeconds) { }

    /** 输了的代价档和逃脱的条件。 */
    public record Outcome(LossPenalty lossPenalty, double escapeDistance, int escapeUnseenSeconds,
                          int escapeTimeLimitDays) { }
}
