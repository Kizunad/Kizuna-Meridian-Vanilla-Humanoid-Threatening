package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.config.TomlFile;
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
import java.util.Optional;
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
    private static final String ARMORED_CHEST_ARMOR = "armored_chest_armor";
    private static final String STRIKE_START_DISTANCE = "strike_start_distance";
    private static final String KICK_START_DISTANCE = "kick_start_distance";
    private static final String RETURN_UNPRESSED_SECONDS = "return_unpressed_seconds";
    private static final String NOTICE_DISTANCE = "notice_distance";
    private static final String VILLAGER = "villager";
    /** 原版视线判定在这个距离外一律不成立，盯上距离再远也看不见。 */
    private static final double MAX_NOTICE_DISTANCE = 128;
    private static final double FULL_CIRCLE_DEGREES = 360;
    private static final String VOLUMES = "volumes";
    private static final String POOL = "volume_pool";
    private static final TomlFile TOML =
            new TomlFile(HumanoidErrors.BRAIN + " " + DEFAULT_RESOURCE);
    private static final Logger LOGGER = System.getLogger("meridian_humanoid_brain_config");

    private HumanoidBrainConfigLoader() {
        // 配置加载器不持有服务器实例或可变配置。
    }

    private static TomlTable defaultsTable() {
        // 旧配置缺项时，从同一份随包资源读取对应规则。
        return TOML.parse(TomlFile.resource(HumanoidBrainConfigLoader.class, DEFAULT_RESOURCE));
    }

    public static HumanoidBrainConfig defaults() {
        // JAR 资源是默认值唯一来源。
        return parse(TomlFile.resource(HumanoidBrainConfigLoader.class, DEFAULT_RESOURCE));
    }

    public static HumanoidBrainConfig load(Path path) {
        // 首次加载导出默认文件；已有文件只读取，不覆盖运营方调整。
        boolean existing = path != null && Files.exists(path);
        String text = TomlFile.readOrExport(path, HumanoidBrainConfigLoader.class, DEFAULT_RESOURCE);
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
        return defaultsTable().keyPathSet().stream()
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
                "assessment", "response", VOLUMES, "concurrency", VILLAGER, POOL), "");
        int version = TOML.positiveInteger(parsed, "config_version", "config_version");
        if (version < MIN_SUPPORTED_CONFIG_VERSION || version > CURRENT_CONFIG_VERSION) {
            throw TOML.invalid("config_version 必须位于 " + MIN_SUPPORTED_CONFIG_VERSION + "～"
                    + CURRENT_CONFIG_VERSION + "，请删除旧文件或按新默认值合并");
        }
        var schedule = new HumanoidBrainConfig.Schedule(
                TOML.positiveInteger(parsed, "decision_interval_ticks", "decision_interval_ticks"), log(parsed));
        var threat = threat(TOML.table(parsed, "threat", "threat"));
        var assessment = assessment(TOML.table(parsed, "assessment", "assessment"), threat.safeDistance());
        var response = response(TOML.table(parsed, "response", "response"), threat.safeDistance());
        var volumes = volumes(TOML.table(parsed, VOLUMES, VOLUMES));
        var concurrency = concurrency(parsed);
        return new HumanoidBrainConfig(schedule, threat, assessment, response, volumes, concurrency,
                villager(parsed), volumePool(parsed));
    }

    private static VolumePool volumePool(TomlTable parsed) {
        var source = parsed;
        if (!parsed.contains(POOL)) {
            source = defaultsTable();
        }
        var table = TOML.table(source, POOL, POOL);
        TOML.checkKeys(table, Set.of("wandering_chance", "true_chance", "middle_chance", "books",
                "wandering_iron_chance", "diamond_chance"), POOL);
        var books = TOML.table(table, "books", POOL + ".books");
        var entries = new ArrayList<VolumePool.Book>();
        for (String id : books.keySet()) {
            entries.add(poolBook(id, TOML.table(books, id, POOL + ".books." + id)));
        }
        var defaults = VolumePool.Materials.DEFAULT;
        var materials = new VolumePool.Materials(poolChance(table, "wandering_iron_chance",
                defaults.wanderingIronChance()), poolChance(table, "diamond_chance", defaults.diamondChance()));
        return new VolumePool(ratio(table, "wandering_chance", POOL), ratio(table, "true_chance", POOL),
                ratio(table, "middle_chance", POOL), entries, materials);
    }

    private static double poolChance(TomlTable table, String key, double defaultValue) {
        if (!table.contains(key)) {
            return defaultValue;
        }
        return ratio(table, key, POOL);
    }

    private static VolumePool.Book poolBook(String id, TomlTable table) {
        requireId(id, POOL);
        TOML.checkKeys(table, Set.of("native", "weight", "upper", "middle"), id);
        var nativeId = table.getString("native");
        if (HumanoidSpecies.values().stream().noneMatch(species -> species.entityId().equals(nativeId))) {
            throw TOML.invalid(id + ".native 必须是七种生人之一");
        }
        var weight = table.getLong("weight");
        if (weight == null || weight < 0 || weight > Integer.MAX_VALUE) {
            throw TOML.invalid(id + ".weight 必须是非负整数");
        }
        var upper = table.getArray("upper");
        if (upper == null || upper.isEmpty()) {
            throw TOML.invalid(id + ".upper 必须列出入门卷");
        }
        var ids = new ArrayList<String>();
        for (Object value : upper.toList()) {
            if (!(value instanceof String volume)) {
                throw TOML.invalid(id + ".upper 必须是卷 ID");
            }
            requireId(volume, id);
            ids.add(volume);
        }
        var middle = Optional.ofNullable(table.getString("middle"));
        middle.ifPresent(volume -> requireId(volume, id));
        if (Set.copyOf(ids).size() != ids.size() || middle.filter(ids::contains).isPresent()) {
            throw TOML.invalid(id + " 重复列卷");
        }
        return new VolumePool.Book(id, nativeId, weight.intValue(), ids, middle);
    }

    private static HumanoidBrainConfig.VillagerRules villager(TomlTable parsed) {
        // 这一节在旧导出文件里没有：整节缺失时读 JAR 默认资源的同名节，load 启动时另行提示缺键。
        TomlTable source = parsed;
        if (!parsed.contains(VILLAGER)) {
            source = defaultsTable();
        }
        TomlTable table = TOML.table(source, VILLAGER, VILLAGER);
        TOML.checkKeys(table, Set.of("sortie_distance", "sortie_reconsider_seconds", "outnumbered_weight",
                "guard_approach_speed", "reload_approach_speed"),
                VILLAGER + ".");
        // 旧装填速度键仅允许读取旧导出文件，不再参与速度选择。
        double approachSpeed = HumanoidBrainConfig.VillagerRules.DEFAULT_GUARD_APPROACH_SPEED;
        if (table.contains("guard_approach_speed")) {
            approachSpeed = positive(table, "guard_approach_speed", VILLAGER);
        }
        return new HumanoidBrainConfig.VillagerRules(positive(table, "sortie_distance", VILLAGER),
                positive(table, "sortie_reconsider_seconds", VILLAGER),
                nonNegative(table, "outnumbered_weight", VILLAGER), approachSpeed);
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
        TOML.checkKeys(table, Set.of("memory_seconds", "ally_radius", "forget_distance", "safe_distance",
                NOTICE_DISTANCE, ThreatScoringLoader.SCORE, "guard"), "threat.");
        return new HumanoidBrainConfig.ThreatRules(positive(table, "memory_seconds", "threat"),
                positive(table, "ally_radius", "threat"),
                positive(table, "forget_distance", "threat"),
                positive(table, "safe_distance", "threat"),
                noticeDistances(table),
                ThreatScoringLoader.parse(TOML, TOML.table(withDefault(table, ThreatScoringLoader.SCORE),
                        ThreatScoringLoader.SCORE, "threat." + ThreatScoringLoader.SCORE)), guard(table));
    }

    private static GuardResponse guard(TomlTable threat) {
        String path = "threat.guard";
        TomlTable table = TOML.table(withDefault(threat, "guard"), "guard", path);
        TOML.checkKeys(table, Set.of("melee_enter", "melee_exit", "ranged", "angle_degrees", "quiet_millis",
                "rest_millis", "recent_melee_millis", "turn_degrees_per_tick", "mortal", "wandering_qi", "true_qi"),
                path + ".");
        var range = new GuardResponse.Range(nonNegative(table, "melee_enter", path),
                nonNegative(table, "melee_exit", path), nonNegative(table, "ranged", path));
        if (range.meleeExit() < range.meleeEnter()) {
            throw TOML.invalid(path + ".melee_exit 不能小于 melee_enter");
        }
        double angle = nonNegative(table, "angle_degrees", path);
        if (angle > FULL_CIRCLE_DEGREES) {
            throw TOML.invalid(path + ".angle_degrees 不能超过一整圈");
        }
        var tiers = new EnumMap<HumanoidTier, GuardResponse.Reaction>(HumanoidTier.class);
        for (HumanoidTier tier : HumanoidTier.values()) {
            String tierPath = path + "." + tier.id();
            var profile = TOML.table(table, tier.id(), tierPath);
            TOML.checkKeys(profile, Set.of("chance", "delay_millis"), tierPath + ".");
            tiers.put(tier, new GuardResponse.Reaction(ratio(profile, "chance", tierPath),
                    millis(profile, "delay_millis", tierPath)));
        }
        var timing = new GuardResponse.Timing(millis(table, "recent_melee_millis", path),
                millis(table, "quiet_millis", path), millis(table, "rest_millis", path));
        return new GuardResponse(range, angle, nonNegative(table, "turn_degrees_per_tick", path), timing, tiers);
    }

    private static TomlTable withDefault(TomlTable threat, String key) {
        // 后加的子表在旧导出文件里没有：缺失时改读 JAR 默认资源的 [threat]，load 启动时另行提示缺键。
        if (threat.contains(key)) {
            return threat;
        }
        return TOML.table(defaultsTable(), "threat", "threat");
    }

    private static Map<String, Double> noticeDistances(TomlTable threat) {
        // 每项是带命名空间的实体 ID 对一个距离，距离为正且不超过原版视线判定的上限；表缺失时读默认。
        String path = "threat." + NOTICE_DISTANCE;
        TomlTable table = TOML.table(withDefault(threat, NOTICE_DISTANCE), NOTICE_DISTANCE, path);
        var distances = new HashMap<String, Double>();
        for (String entityId : table.keySet()) {
            requireId(entityId, path + "." + entityId);
            double distance = positive(table, entityId, path);
            if (distance > MAX_NOTICE_DISTANCE) {
                throw TOML.invalid(path + "." + entityId + " 不能超过 " + MAX_NOTICE_DISTANCE);
            }
            distances.put(entityId, distance);
        }
        return distances;
    }

    private static AssessmentRules assessment(TomlTable table, double safeDistance) {
        // 权重非负，门槛为 [0, 1] 比例；对敌余量可以为负；没被逼的秒数为正。
        TOML.checkKeys(table, Set.of("realm_weight", "strike_weight", "armed_weight", "body_weight", "qi_weight",
                "casting_weight", "engage_margin", "retreat_hp_ratio", "retreat_part_hp_ratio",
                "arm_exhausted_ratio", "return_hp_ratio", "return_arm_ratio", RETURN_UNPRESSED_SECONDS),
                "assessment.");
        var weights = new AssessmentRules.Weights(nonNegative(table, "realm_weight", "assessment"),
                nonNegative(table, "strike_weight", "assessment"),
                nonNegative(table, "armed_weight", "assessment"),
                nonNegative(table, "body_weight", "assessment"),
                nonNegative(table, "qi_weight", "assessment"));
        var retreat = new AssessmentRules.Retreat(ratio(table, "retreat_hp_ratio", "assessment"),
                ratio(table, "retreat_part_hp_ratio", "assessment"),
                ratio(table, "arm_exhausted_ratio", "assessment"));
        var recovery = new AssessmentRules.Recovery(ratio(table, "return_hp_ratio", "assessment"),
                ratio(table, "return_arm_ratio", "assessment"), safeDistance, returnUnpressedSeconds(table));
        return new AssessmentRules(weights, nonNegative(table, "casting_weight", "assessment"),
                TOML.finiteNumber(table, "engage_margin", "assessment.engage_margin"), retreat, recovery);
    }

    private static HumanoidBrainConfig.ResponseRules response(TomlTable table, double safeDistance) {
        // 毫秒为非负整数，剂量比为 [0, 1]，失败次数至少一次，站定距离、起手距离和甲厚门槛为正。
        TOML.checkKeys(table, Set.of("reaction_window_millis", "interrupt_margin_millis", "absorb_own_hit_millis",
                "absorb_dose_ratio", "dodge_margin_millis", "assault_stand_off", "failure_limit",
                "failure_block_millis", CORNERED_STAND_DISTANCE, ARMORED_CHEST_ARMOR, STRIKE_START_DISTANCE,
                KICK_START_DISTANCE), "response.");
        var reaction = new TacticRules.Reaction(millis(table, "reaction_window_millis"),
                millis(table, "interrupt_margin_millis"),
                millis(table, "absorb_own_hit_millis"),
                ratio(table, "absorb_dose_ratio", "response"),
                millis(table, "dodge_margin_millis"));
        var tactics = new TacticRules(reaction, safeDistance, corneredStandDistance(table),
                positive(defaulted(table, STRIKE_START_DISTANCE), STRIKE_START_DISTANCE, "response"),
                positive(defaulted(table, KICK_START_DISTANCE), KICK_START_DISTANCE, "response"));
        return new HumanoidBrainConfig.ResponseRules(tactics,
                positive(table, "assault_stand_off", "response"),
                TOML.positiveInteger(table, "failure_limit", "response.failure_limit"),
                millis(table, "failure_block_millis"),
                positive(defaulted(table, ARMORED_CHEST_ARMOR), ARMORED_CHEST_ARMOR, "response"));
    }

    private static TomlTable defaulted(TomlTable table, String key) {
        // [response] 里后加的键在旧导出文件里没有：缺失时读 JAR 默认资源的同名键，load 启动时另行提示。
        if (table.contains(key)) {
            return table;
        }
        return TOML.table(defaultsTable(), "response", "response");
    }

    private static double returnUnpressedSeconds(TomlTable table) {
        // 这个键在旧导出文件里没有：缺失时读 JAR 默认资源的同名键，load 启动时另行提示。
        TomlTable source = table;
        if (!table.contains(RETURN_UNPRESSED_SECONDS)) {
            source = TOML.table(defaultsTable(), "assessment", "assessment");
        }
        return positive(source, RETURN_UNPRESSED_SECONDS, "assessment");
    }

    private static double corneredStandDistance(TomlTable table) {
        // 这个键在旧导出文件里没有：缺失时读 JAR 默认资源的同名键，load 启动时另行提示。
        TomlTable source = table;
        if (!table.contains(CORNERED_STAND_DISTANCE)) {
            source = TOML.table(defaultsTable(), "response", "response");
        }
        return positive(source, CORNERED_STAND_DISTANCE, "response");
    }

    public static VolumeTable addonVolumes(String text) {
        // 功法包随模组带的学卷片段：只有 [volumes."<实体 ID>"] 一节，写法和配置文件里的学卷表相同，只写自己要加的物种和档位。
        var parsed = TOML.parse(text);
        TOML.checkKeys(parsed, Set.of(VOLUMES), "");
        return species(TOML.table(parsed, VOLUMES, VOLUMES));
    }

    private static VolumeTable volumes(TomlTable table) {
        // 七种内置类人必须全部出现；其他命名空间实体 ID 也接受，供另行注册的类人使用。
        VolumeTable volumes = species(table);
        for (HumanoidSpecies builtIn : HumanoidSpecies.values()) {
            if (!volumes.species().containsKey(builtIn.entityId())) {
                throw TOML.invalid("学卷表缺少 " + builtIn.entityId());
            }
        }
        return volumes;
    }

    private static VolumeTable species(TomlTable table) {
        // 每个实体 ID 一张档位表。
        var species = new HashMap<String, Map<HumanoidTier, List<VolumeTable.Grant>>>();
        for (String entityId : table.keySet()) {
            String path = "volumes." + entityId;
            requireId(entityId, path);
            species.put(entityId, tiers(TOML.table(table, entityId, path), path));
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

    static void requireId(String id, String path) {
        // 实体与卷 ID 都必须带命名空间。
        int separator = id.indexOf(':');
        if (separator <= 0 || separator == id.length() - 1) {
            throw TOML.invalid(path + " 必须是命名空间 ID");
        }
    }

    private static double ratio(TomlTable table, String key, String section) {
        // 比例与概率位于 [0, 1]。
        double value = TOML.finiteNumber(table, key, section + "." + key);
        if (value < 0 || value > 1) {
            throw TOML.invalid(section + "." + key + " 必须位于 [0, 1]");
        }
        return value;
    }

    private static double nonNegative(TomlTable table, String key, String section) {
        // 权重不能为负。
        double value = TOML.finiteNumber(table, key, section + "." + key);
        if (value < 0) {
            throw TOML.invalid(section + "." + key + " 不能为负");
        }
        return value;
    }

    private static double positive(TomlTable table, String key, String section) {
        // 距离与时长必须为正。
        double value = TOML.finiteNumber(table, key, section + "." + key);
        if (value <= 0) {
            throw TOML.invalid(section + "." + key + " 必须为正");
        }
        return value;
    }

    private static long millis(TomlTable table, String key) {
        return millis(table, key, "response");
    }

    private static long millis(TomlTable table, String key, String section) {
        // 毫秒只接受非负整数，不通过 double 截断。
        if (!(table.get(List.of(key)) instanceof Long value) || value < 0) {
            throw TOML.invalid(section + "." + key + " 必须是非负整数");
        }
        return value;
    }
}
