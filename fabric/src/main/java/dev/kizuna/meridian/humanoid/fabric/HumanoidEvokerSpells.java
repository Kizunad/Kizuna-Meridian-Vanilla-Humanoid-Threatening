package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.illager.Evoker;
import net.minecraft.world.entity.monster.illager.SpellcasterIllager;

/**
 * 修炼者唤魔者和原版法术的接缝（C6c SPEC 4.4、4.5、6.2、6.3）：功法和原版法术互斥、新目标先等大脑决策、
 * 修炼者不变羊、唤魔者起附伤式时念 prepare_wololo、起手期间双手冒本行颜色的粒子。mixin 只转调这里。
 */
public final class HumanoidEvokerSpells {
    // 手位照原版施法粒子（SpellcasterIllager.tick）：离身体中线 0.6 格、高 1.8 格，朝向随 tick 小幅摆动。
    private static final double HAND_SPREAD = 0.6;
    private static final double HAND_HEIGHT = 1.8;
    private static final float SWING_RATE = 0.6662F;
    private static final float SWING_ANGLE = 0.25F;
    private static final float WOLOLO_VOLUME = 1.0F;
    private static final float WOLOLO_PITCH = 1.0F;
    private static final long MILLIS_PER_TICK = 50;
    // 本行颜色（原稿 §8.5 占位）：金白、土黄、火红、水黑、木青。
    private static final Map<String, ColorParticleOption> HAND_PARTICLES = Map.of(
            "meridian:metal", color(0.95F, 0.95F, 0.9F),
            "meridian:earth", color(0.85F, 0.7F, 0.2F),
            "meridian:fire", color(0.9F, 0.2F, 0.1F),
            "meridian:water", color(0.08F, 0.08F, 0.12F),
            "meridian:wood", color(0.2F, 0.75F, 0.7F));
    private static final Map<UUID, Warmup> warmups = new HashMap<>();
    private static final Map<UUID, Sighting> sightings = new HashMap<>();

    /** 一次附伤式的起手：哪只唤魔者、哪一次施放、冒什么粒子、到哪一 tick 附行生效。 */
    private record Warmup(Evoker evoker, String castId, ColorParticleOption particle, long endTick) { }

    /** 原版法术第一次看到这个目标、大脑还没接上它的那一 tick。 */
    private record Sighting(LivingEntity target, long tick) { }

    private HumanoidEvokerSpells() {
        // 纯静态入口，状态只有起手中的粒子表。
    }

    static void initialize() {
        // 施放结束（含被打断）就停粒子；卸载与停服清两张表。
        TechniqueFabric.CAST_ENDED.register(event -> {
            Warmup current = warmups.get(event.entityUuid());
            if (current != null && current.castId().equals(event.castId())) {
                warmups.remove(event.entityUuid());
            }
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            warmups.remove(entity.getUUID());
            sightings.remove(entity.getUUID());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            warmups.clear();
            sightings.clear();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> tickWarmups());
    }

    public static boolean blocksSpell(SpellcasterIllager caster) {
        // 正施功法时原版法术不开始；原版冷却照走，不因功法改变。
        return HumanoidTechniqueHost.hasActiveCast(caster) || awaitingBrain(caster);
    }

    public static boolean blocksWololo(Evoker evoker) {
        // 修炼者不变羊，凡人照原版。
        return cultivator(evoker);
    }

    private static boolean awaitingBrain(SpellcasterIllager caster) {
        // 原版法术在设目标后一两 tick 就起，大脑按错相要到下一次决策才看见这个目标（C6c 第 3 段补 SPEC 4.4）。
        // 修炼者唤魔者的原版法术先等大脑接上这个目标，最多等一个决策间隔；没接上（比如大脑选了更近的威胁）
        // 等满也放行，不会一直压着原版。
        LivingEntity target = caster.getTarget();
        if (!(caster instanceof Evoker evoker) || target == null || HumanoidBrain.config() == null
                || HumanoidBrain.opponent(evoker).orElse(null) == target) {
            sightings.remove(caster.getUUID());
            return false;
        }
        long now = caster.level().getGameTime();
        Sighting sighting = sightings.compute(caster.getUUID(), (id, seen) ->
                seen != null && seen.target() == target ? seen : new Sighting(target, now));
        int interval = HumanoidBrain.config().schedule().decisionIntervalTicks();
        return now - sighting.tick() < interval && cultivator(evoker);
    }

    private static boolean cultivator(Evoker evoker) {
        // 档位取实际身体，还没出生的这时按自然概率出生；宿主还没装配好（开服第一个 tick 前）时查不了身体，
        // 先当凡人照原版。
        if (!HumanoidFabric.isReady()) {
            return false;
        }
        var body = HumanoidHealthHost.bodyState(evoker, HumanoidSpecies.EVOKER, HumanoidFabric.now());
        return HumanoidTier.of(body.snapshot()) != HumanoidTier.MORTAL;
    }

    static boolean castingSpell(Mob mob) {
        // 原版施法期间不起功法，两边都是两手举起的施法动作。
        return mob instanceof SpellcasterIllager caster && caster.isCastingSpell();
    }

    static void castStarted(Mob mob, TechniqueReference technique, String castId) {
        // 唤魔者起附伤式成功后在 0 ms 念咒，归敌对生物；起手到附行生效前双手冒本行颜色的粒子。
        if (!(mob instanceof Evoker evoker)) {
            return;
        }
        Optional<TechniqueEffect> infusion = TechniqueFabric.service().registry().find(technique).orElseThrow()
                .getEffectsList().stream()
                .filter(TechniqueEffect::hasElementalInfusion)
                .findFirst();
        if (infusion.isEmpty()) {
            return;
        }
        evoker.level().playSound(null, evoker.getX(), evoker.getY(), evoker.getZ(), SoundEvents.EVOKER_PREPARE_WOLOLO,
                SoundSource.HOSTILE, WOLOLO_VOLUME, WOLOLO_PITCH);
        String attribute = infusion.get().getElementalInfusion().getAttribute();
        ColorParticleOption particle = Optional.ofNullable(HAND_PARTICLES.get(attribute))
                .orElseThrow(() -> new IllegalStateException("附伤式的属性没有手上粒子颜色：" + attribute));
        long endTick = evoker.level().getGameTime() + infusion.get().getAtMillis() / MILLIS_PER_TICK;
        warmups.put(evoker.getUUID(), new Warmup(evoker, castId, particle, endTick));
    }

    private static void tickWarmups() {
        // 每 tick 每只手一颗，和原版施法粒子同频；附行生效、实体没了就停。
        warmups.values().removeIf(warmup -> {
            Evoker evoker = warmup.evoker();
            if (evoker.isRemoved() || !evoker.isAlive() || evoker.level().getGameTime() >= warmup.endTick()) {
                return true;
            }
            emitAtHands(evoker, warmup.particle());
            return false;
        });
    }

    private static void emitAtHands(Evoker evoker, ColorParticleOption particle) {
        // 左右手各一颗，位置算法同原版施法粒子，只是改在服务端发。
        float angle = evoker.yBodyRot * Mth.DEG_TO_RAD + Mth.cos(evoker.tickCount * SWING_RATE) * SWING_ANGLE;
        double spread = HAND_SPREAD * evoker.getScale();
        double offsetX = Mth.cos(angle) * spread;
        double offsetZ = Mth.sin(angle) * spread;
        double y = evoker.getY() + HAND_HEIGHT * evoker.getScale();
        ServerLevel level = (ServerLevel) evoker.level();
        level.sendParticles(particle, evoker.getX() + offsetX, y, evoker.getZ() + offsetZ, 1, 0, 0, 0, 0);
        level.sendParticles(particle, evoker.getX() - offsetX, y, evoker.getZ() - offsetZ, 1, 0, 0, 0, 0);
    }

    private static ColorParticleOption color(float red, float green, float blue) {
        // 原版施法粒子同一种类型，只换颜色。
        return ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, red, green, blue);
    }
}
