package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidSpeciesDefinitions;
import dev.kizuna.meridian.humanoid.server.SpeciesBodyRules;
import dev.kizuna.meridian.protocol.pb.AddonMessages.AddonBundle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

/** 模组初始化时提交一张物种表及共用的办法，世界启动前统一安装内容包。 */
public final class HumanoidSpeciesHooks {
    private static final Map<HumanoidSpecies, Predicate<LivingEntity>> claims = new HashMap<>();
    private static final List<AddonBundle> content = new ArrayList<>();
    private static final Map<EntityType<?>, Optional<HumanoidSpecies>> byType = new ConcurrentHashMap<>();
    private static final AtomicInteger typeResolutions = new AtomicInteger();
    private static volatile boolean installed;

    private HumanoidSpeciesHooks() { }

    public static synchronized List<HumanoidSpecies> registerAll(String table, SpeciesBodyRules rules,
            Predicate<LivingEntity> accepts, List<AddonBundle> bundles) {
        if (installed) {
            throw new IllegalStateException("类人物种内容目录已安装，须在模组初始化时登记");
        }
        Objects.requireNonNull(accepts, "accepts");
        var additions = List.copyOf(bundles);
        var species = HumanoidSpeciesDefinitions.registerAll(table, rules);
        species.forEach(entry -> claims.put(entry, accepts));
        content.addAll(additions);
        byType.clear();
        return species;
    }

    static synchronized List<AddonBundle> freezeContent() {
        installed = true;
        return List.copyOf(content);
    }

    static Optional<HumanoidSpecies> claimed(LivingEntity entity) {
        var species = species(entity.getType());
        if (species.isPresent() && excludes(species.get(), entity)) {
            return Optional.empty();
        }
        return species;
    }

    static boolean excluded(LivingEntity entity) {
        var species = species(entity.getType());
        return species.isPresent() && excludes(species.get(), entity);
    }

    private static boolean excludes(HumanoidSpecies species, LivingEntity entity) {
        var accepts = claims.get(species);
        return accepts != null && !accepts.test(entity);
    }

    private static Optional<HumanoidSpecies> species(EntityType<?> type) {
        // 旧的纯规则入口也能在初始化期间登记。目录冻结之后，命中和未命中都按原版类型复用。
        if (!installed) {
            return resolveType(type);
        }
        return byType.computeIfAbsent(type, HumanoidSpeciesHooks::resolveType);
    }

    private static Optional<HumanoidSpecies> resolveType(EntityType<?> type) {
        typeResolutions.incrementAndGet();
        return HumanoidSpecies.fromEntityId(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
    }

    static int typeResolutions() {
        return typeResolutions.get();
    }
}
