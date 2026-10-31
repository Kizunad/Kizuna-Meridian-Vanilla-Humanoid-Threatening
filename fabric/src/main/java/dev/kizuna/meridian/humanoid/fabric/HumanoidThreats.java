package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidBrainConfig;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;

/** 敌意来源：谁以谁为目标、谁打过我或同伴、谁算同伴；只记实体引用与到期刻，不读身体、不扫描周围。 */
final class HumanoidThreats {
    private static final double TICKS_PER_SECOND = 20;
    private static final double SURROUNDED_RADIUS = 3;
    private static final int SURROUNDED_COUNT = 2;
    private static final Map<LivingEntity, Set<Mob>> targetedBy = new WeakHashMap<>();
    private static final Map<LivingEntity, Map<LivingEntity, Long>> attackers = new WeakHashMap<>();

    /** 原版阵营：袭击者一方、村庄一方（商人与铁傀儡），其余无阵营。 */
    enum Faction { RAIDERS, VILLAGE, NONE }

    private HumanoidThreats() {
        // 登记表按实体弱引用保存，实体卸载后自然释放。
    }

    static void targeted(Mob mob, LivingEntity previous, LivingEntity next) {
        // 原版改目标时同步反查表，供「以我为目标的活体」查询。
        if (previous != null) {
            var set = targetedBy.get(previous);
            if (set != null) {
                set.remove(mob);
            }
        }
        if (next != null) {
            targetedBy.computeIfAbsent(next, ignored -> Collections.newSetFromMap(new WeakHashMap<>())).add(mob);
        }
    }

    static void damaged(VanillaDamageFabric.DamageEvent event) {
        // 受击者与其同伴记住攻击者；同伴误伤不记，避免同阵营因误伤互殴。
        LivingEntity victim = event.target();
        LivingEntity attacker = event.attacker();
        HumanoidBrainConfig config = HumanoidBrain.config();
        if (attacker == null || attacker == victim || allied(victim, attacker) || config == null) {
            return;
        }
        var rules = config.threat();
        long expiry = victim.level().getGameTime() + Math.round(rules.memorySeconds() * TICKS_PER_SECOND);
        remember(victim, attacker, expiry);
        if (faction(victim) == Faction.NONE) {
            return;
        }
        var box = victim.getBoundingBox().inflate(rules.allyRadius());
        for (Mob ally : victim.level().getEntitiesOfClass(Mob.class, box,
                other -> other != victim && allied(victim, other) && HumanoidBrain.managed(other))) {
            remember(ally, attacker, expiry);
        }
    }

    private static void remember(LivingEntity victim, LivingEntity attacker, long expiry) {
        // 同一攻击者再次出手只延长到期刻。
        attackers.computeIfAbsent(victim, ignored -> new WeakHashMap<>()).put(attacker, expiry);
    }

    static Optional<LivingEntity> nearest(Mob mob, HumanoidBrainConfig.ThreatRules rules,
                                          Optional<LivingEntity> previous) {
        // 只看事件登记与原版已有的敌意，取最近的一个；远于放下距离且看不见的不算。
        var candidates = candidates(mob, previous);
        double forget = rules.forgetDistance();
        return candidates.stream()
                .filter(candidate -> valid(mob, candidate))
                .filter(candidate -> mob.distanceToSqr(candidate) <= forget * forget || mob.hasLineOfSight(candidate))
                .min(Comparator.comparingDouble(mob::distanceToSqr));
    }

    private static List<LivingEntity> candidates(Mob mob, Optional<LivingEntity> previous) {
        // 我的目标、上次的对手、攻击我的、以我为目标的、受击记忆，以及村民的敌对与受击记忆。
        var candidates = new ArrayList<LivingEntity>();
        previous.ifPresent(candidates::add);
        addIfPresent(candidates, mob.getTarget());
        addIfPresent(candidates, mob.getLastHurtByMob());
        var hunters = targetedBy.get(mob);
        if (hunters != null) {
            hunters.stream().filter(hunter -> hunter.getTarget() == mob).forEach(candidates::add);
        }
        var remembered = attackers.get(mob);
        if (remembered != null) {
            long now = mob.level().getGameTime();
            remembered.values().removeIf(expiry -> expiry < now);
            candidates.addAll(remembered.keySet());
        }
        if (mob instanceof Villager villager) {
            var brain = villager.getBrain();
            brain.getMemory(MemoryModuleType.NEAREST_HOSTILE).ifPresent(candidates::add);
            brain.getMemory(MemoryModuleType.HURT_BY_ENTITY).ifPresent(candidates::add);
        }
        return candidates;
    }

    private static void addIfPresent(List<LivingEntity> candidates, LivingEntity candidate) {
        // 原版可空引用统一在这里过滤。
        if (candidate != null) {
            candidates.add(candidate);
        }
    }

    private static boolean valid(Mob mob, LivingEntity candidate) {
        // 同一世界的存活实体；旁观与创造模式玩家不算敌人。
        if (candidate == mob || !candidate.isAlive() || candidate.isRemoved()
                || candidate.level() != mob.level() || candidate.isSpectator()) {
            return false;
        }
        return !(candidate instanceof Player player) || !player.isCreative();
    }

    static boolean enemy(LivingEntity self, LivingEntity other) {
        // 我的对手、我的目标、以我为目标的，或在受击记忆里的。
        if (self instanceof Mob mob && (mob.getTarget() == other
                || HumanoidBrain.opponent(mob).filter(opponent -> opponent == other).isPresent())) {
            return true;
        }
        if (other instanceof Mob hunter && hunter.getTarget() == self) {
            return true;
        }
        var remembered = attackers.get(self);
        return remembered != null && remembered.getOrDefault(other, Long.MIN_VALUE) >= self.level().getGameTime();
    }

    static boolean hittable(LivingEntity actor, LivingEntity target) {
        // 打击不伤同伴，除非对方正是我的敌人。
        return !allied(actor, target) || enemy(actor, target);
    }

    static boolean surrounded(Mob mob) {
        // 以我为目标的近身敌人达到两个即为被围；旁观的同伴和路人不算。
        var hunters = targetedBy.getOrDefault(mob, Set.of());
        long close = hunters.stream()
                .filter(hunter -> hunter.getTarget() == mob && hunter.isAlive())
                .filter(hunter -> mob.distanceToSqr(hunter) <= SURROUNDED_RADIUS * SURROUNDED_RADIUS)
                .count();
        return close >= SURROUNDED_COUNT;
    }

    static boolean allied(LivingEntity self, LivingEntity other) {
        // 同队伍，或同属一个非空原版阵营。
        Faction faction = faction(self);
        return self.isAlliedTo(other) || faction != Faction.NONE && faction == faction(other);
    }

    static Faction faction(LivingEntity entity) {
        // 女巫也是袭击者；村民、流浪商人与铁傀儡同属村庄。
        if (entity instanceof Raider) {
            return Faction.RAIDERS;
        }
        if (entity instanceof AbstractVillager || entity instanceof IronGolem) {
            return Faction.VILLAGE;
        }
        return Faction.NONE;
    }

    static void clear() {
        // 服务器停止时丢弃全部登记。
        targetedBy.clear();
        attackers.clear();
    }
}
