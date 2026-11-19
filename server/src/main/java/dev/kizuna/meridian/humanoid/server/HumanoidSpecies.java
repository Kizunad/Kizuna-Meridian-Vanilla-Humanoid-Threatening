package dev.kizuna.meridian.humanoid.server;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 由 Threatening 认领为身体宿主的实体物种：七种原版生人固定内置，其他模组可在世界加载前按实体 ID 登记新物种。
 * 每个实体 ID 只对应一个实例，可以用 == 比较。
 */
public final class HumanoidSpecies {
    public static final HumanoidSpecies VILLAGER = new HumanoidSpecies("VILLAGER", "minecraft:villager", null);
    public static final HumanoidSpecies WANDERING_TRADER = new HumanoidSpecies("WANDERING_TRADER",
            "minecraft:wandering_trader", null);
    public static final HumanoidSpecies PILLAGER = new HumanoidSpecies("PILLAGER", "minecraft:pillager", null);
    public static final HumanoidSpecies VINDICATOR = new HumanoidSpecies("VINDICATOR", "minecraft:vindicator", null);
    public static final HumanoidSpecies EVOKER = new HumanoidSpecies("EVOKER", "minecraft:evoker", null);
    public static final HumanoidSpecies WITCH = new HumanoidSpecies("WITCH", "minecraft:witch", null);
    public static final HumanoidSpecies ILLUSIONER = new HumanoidSpecies("ILLUSIONER", "minecraft:illusioner", null);
    private static final List<HumanoidSpecies> BUILT_IN = List.of(VILLAGER, WANDERING_TRADER, PILLAGER, VINDICATOR,
            EVOKER, WITCH, ILLUSIONER);
    private static final Map<String, HumanoidSpecies> BY_ID = new ConcurrentHashMap<>();

    static {
        BUILT_IN.forEach(species -> BY_ID.put(species.entityId, species));
    }

    private final String name;
    private final String entityId;
    private final HumanoidSpeciesDefinition definition;
    private final SpeciesBodyRules bodyRules;

    private HumanoidSpecies(String name, String entityId, HumanoidSpeciesDefinition definition) {
        this(name, entityId, definition, SpeciesBodyRules.DEFAULT);
    }

    private HumanoidSpecies(String name, String entityId, HumanoidSpeciesDefinition definition,
                            SpeciesBodyRules bodyRules) {
        this.bodyRules = bodyRules;
        this.name = name;
        this.entityId = entityId;
        this.definition = definition;
    }

    public static List<HumanoidSpecies> values() {
        // 只返回七种内置物种；它们必须出现在出生配置与学卷表中，登记物种另由 registered() 列出。
        return BUILT_IN;
    }

    public static List<HumanoidSpecies> registered() {
        // 运行时登记的物种，按实体 ID 排序，便于命令和日志输出稳定。
        return BY_ID.values().stream()
                .filter(species -> species.definition != null)
                .sorted(Comparator.comparing(HumanoidSpecies::entityId))
                .toList();
    }

    public static HumanoidSpecies register(HumanoidSpeciesDefinition definition) {
        return HumanoidSpeciesDefinitions.register(definition);
    }

    static HumanoidSpecies register(HumanoidSpeciesDefinition definition, SpeciesBodyRules rules) {
        // 同一实体 ID 只能登记一次，也不能覆盖内置物种；应在世界加载前的模组初始化阶段调用。
        Objects.requireNonNull(definition, "definition");
        String id = definition.entityId();
        var created = new HumanoidSpecies(id, id, definition, rules);
        if (BY_ID.putIfAbsent(id, created) != null) {
            throw HumanoidErrors.invalid(HumanoidErrors.SPECIES + ": 重复登记 " + id);
        }
        return created;
    }

    public static Optional<HumanoidSpecies> fromEntityId(String entityId) {
        // 未知或空 ID 保持未绑定语义，不能猜测相近的实体。
        if (entityId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_ID.get(entityId));
    }

    public String name() {
        // 内置物种沿用常量名；登记物种以实体 ID 为名。
        return name;
    }

    public String entityId() {
        // 对外返回固定 ID，供 Fabric 宿主建立白名单映射。
        return entityId;
    }

    public Optional<HumanoidSpeciesDefinition> definition() {
        // 内置物种没有登记定义。
        return Optional.ofNullable(definition);
    }

    public SpeciesBodyRules bodyRules() {
        return bodyRules;
    }

    public boolean brainManaged() {
        // 内置物种都由类人大脑接管；登记物种按定义中的 brain 开关。
        return definition == null || definition.control().brain() == HumanoidSpeciesDefinition.Brain.MANAGED;
    }

    @Override
    public String toString() {
        // 日志与断言里直接显示名字。
        return name;
    }
}
