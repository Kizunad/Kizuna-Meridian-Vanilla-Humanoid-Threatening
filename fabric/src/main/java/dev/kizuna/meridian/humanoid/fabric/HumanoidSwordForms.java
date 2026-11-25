package dev.kizuna.meridian.humanoid.fabric;

import com.mojang.serialization.Codec;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.meridian.technique.fabric.world.TechniqueWeaponSplash;
import dev.kizuna.meridian.technique.fabric.world.TechniqueWorldTargets;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ProjectileWeaponItem;

/** 以原版当前目标择式，武器与学卷条件共用同一软剑标签。 */
public final class HumanoidSwordForms {
    private static final String PREFIX = "meridian_piglin_sword:";
    private static final TagKey<Item> SOFT = TagKey.create(Registries.ITEM, Identifier.parse(PREFIX + "soft_blades"));
    private static final List<String> UPPER = List.of(PREFIX + "upper");
    private static final List<String> BOTH = List.of(PREFIX + "upper", PREFIX + "lower");
    static final String SPLASH = PREFIX + "gold_splash";
    private static final AttachmentType<Boolean> SPLASH_SPENT = AttachmentRegistry.create(
            Identifier.parse("meridian_humanoid:gold_splash_spent"), b -> b.persistent(Codec.BOOL));
    private static final List<String> PRIORITY = List.of("gold_splash", "spring_back", "wind_around",
            "coil", "quiver", "molten_flow");
    private static final List<String> ORDINARY = PRIORITY.subList(1, PRIORITY.size());
    private static final double STRIKE_RANGE = 3;
    private static final double FLOW_RANGE = 6;
    private static final int LIGHT_ARMOR = 6;

    private HumanoidSwordForms() { }

    static void initialize() {
        // 挥空也耗剑，被打断或换剑导致释放失败时还保留机会。
        TechniqueWeaponSplash.RELEASED.register((actor, technique) -> {
            if (SPLASH.equals(technique.getId()) && actor instanceof Mob) {
                actor.setAttached(SPLASH_SPENT, Boolean.TRUE);
            }
        });
    }

    static boolean species(LivingEntity entity) {
        var type = entity.getType();
        return type == EntityTypes.PIGLIN || type == EntityTypes.ZOMBIFIED_PIGLIN;
    }

    static List<String> birthVolumes(LivingEntity entity, HumanoidTier tier) {
        if (entity.isBaby() || !entity.isHolding(stack -> stack.is(SOFT))) {
            return List.of();
        }
        return switch (tier) {
            case MORTAL -> List.of();
            case WANDERING_QI -> UPPER;
            case TRUE_QI -> BOTH;
        };
    }

    public static boolean tryCast(Mob caster) {
        // 只读取当前敌人；取目标、寻路、愤怒传播仍由物种自己的 AI 决定。
        return tryCast(caster, caster.getTarget());
    }

    static boolean tryCast(Mob caster, LivingEntity target) {
        // 接受生人大脑已选定的对手，原版物种通过公共入口传当前仇恨目标。
        if (caster.isBaby() || caster.isNoAi() || !caster.isAlive() || target == null
                || !target.isAlive() || !caster.isHolding(stack -> stack.is(SOFT)) || !caster.hasLineOfSight(target)
                || !TechniqueFabric.running() || HumanoidSpeciesHooks.claimed(caster).isEmpty()) {
            return false;
        }
        double distance = caster.distanceTo(target);
        var observation = TechniqueFabric.service().observe(caster.getUUID());
        if (observation.isEmpty()) {
            return false;
        }
        var body = observation.get().body();
        var forms = candidates(caster, distance, body);
        if (forms.isEmpty()) {
            return false;
        }
        var progress = TechniqueState.read(body);
        if (progress.hasActiveCast()) {
            return false;
        }
        long now = body.settledAt().elapsedMillis();
        for (String form : forms) {
            String id = PREFIX + form;
            if (progress.getCooldownsOrDefault(id, 0) > now) {
                continue;
            }
            var reference = progress.getKnownList().stream().map(entry -> entry.getTechnique())
                    .filter(technique -> technique.getId().equals(id)).findFirst();
            if (reference.isEmpty()) {
                continue;
            }
            var technique = reference.orElseThrow();
            if (matches(technique, caster, target, distance, progress)
                    && cast(caster, target, body.id().value(), technique)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> candidates(Mob caster, double distance, BodySnapshot body) {
        // 血条由全身部位投影，直接读同次施放观察里的权威生命比例。
        double threshold = HumanoidBrain.config().response().weapons().splashHealthRatio();
        boolean desperate = body.partHpRatio() < threshold && !caster.hasAttached(SPLASH_SPENT);
        if (species(caster) && distance <= FLOW_RANGE) {
            return desperate ? PRIORITY : ORDINARY;
        }
        if (desperate) {
            return List.of("gold_splash");
        }
        return List.of();
    }

    private static boolean matches(TechniqueReference reference, Mob caster, LivingEntity target, double distance,
                                   TechniqueProgress progress) {
        if (SPLASH.equals(reference.getId())) {
            if (target.isSpectator() || !HumanoidThreats.hittable(caster, target)) {
                return false;
            }
            return TechniqueFabric.service().registry().find(reference).stream()
                    .flatMap(definition -> definition.getEffectsList().stream())
                    .map(effect -> effect.getWeaponStrike())
                    .filter(strike -> strike.hasSplash())
                    .anyMatch(strike -> TechniqueWorldTargets.inStrikeSector(caster, target, strike));
        }
        String form = reference.getId().substring(PREFIX.length());
        if (form.equals("molten_flow")) {
            return !caster.isInWaterOrRain();
        }
        if (distance > STRIKE_RANGE) {
            return false;
        }
        return switch (form) {
            case "spring_back" -> progress.hasBlockedAttack();
            case "wind_around" -> target.isBlocking() || TechniqueFabric.service().activeGuard(target.getUUID())
                    .isPresent();
            case "coil" -> target.isHolding(stack -> stack.has(DataComponents.WEAPON)
                    || stack.getItem() instanceof ProjectileWeaponItem) && hasAlly(caster, target);
            case "quiver" -> target.getArmorValue() <= LIGHT_ARMOR;
            default -> false;
        };
    }

    private static boolean hasAlly(Mob caster, LivingEntity target) {
        // 正在和同一敌人交手的邻近生物才能趁缠住时出手。
        var level = (ServerLevel) caster.level();
        return !level.getEntitiesOfClass(Mob.class, caster.getBoundingBox().inflate(FLOW_RANGE),
                other -> other != caster && other.isAlive() && other.getTarget() == target).isEmpty();
    }

    private static boolean cast(Mob caster, LivingEntity target, String bodyId, TechniqueReference reference) {
        if (!SPLASH.equals(reference.getId())) {
            caster.lookAt(Anchor.EYES, target.getEyePosition());
        }
        var request = CastRequest.newBuilder()
                .setBodyId(bodyId)
                .setTechnique(reference)
                .setTargetId(target.getStringUUID())
                .setCastId(UUID.randomUUID().toString())
                .build();
        return TechniqueFabric.service().cast(caster.getUUID(), request).getPhase() == CastPhase.WARMUP;
    }
}
