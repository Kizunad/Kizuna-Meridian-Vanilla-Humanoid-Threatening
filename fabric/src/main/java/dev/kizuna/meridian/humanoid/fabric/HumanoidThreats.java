package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidBrainConfig;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageTypeTags;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.phys.Vec3;

/** 敌意来源：谁以谁为目标、谁打过我或同伴、谁算同伴；只记实体引用、到期刻与受到的伤害，不读身体、不扫描周围。 */
final class HumanoidThreats {
    private static final long NO_MELEE_TICK = -1;
    private static final double TICKS_PER_SECOND = 20;
    private static final double SURROUNDED_RADIUS = 3;
    private static final int SURROUNDED_COUNT = 2;
    private static final Map<LivingEntity, Set<Mob>> targetedBy = new WeakHashMap<>();
    private static final Map<LivingEntity, Map<LivingEntity, Hostility>> attackers = new WeakHashMap<>();

    /** 原版阵营：袭击者一方、村庄一方（商人与铁傀儡），其余无阵营。 */
    enum Faction { RAIDERS, VILLAGE, NONE }

    /** 一个攻击者在我这里的记录：最近一次伤害后的累计量与时刻，以及保持为敌人的到期刻。 */
    private record Hostility(double damage, long damagedAt, long expiry, long lastMeleeTick) { }

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
        long now = victim.level().getGameTime();
        long expiry = now + memoryTicks(rules);
        var source = event.source();
        long melee = NO_MELEE_TICK;
        if (source.getDirectEntity() == attacker && source.is(VanillaDamageTypeTags.MELEE)) {
            melee = now;
        }
        remember(victim, attacker, new Hostility(event.input().damageAfterEffects(), now, expiry, melee), rules);
        if (faction(victim) == Faction.NONE) {
            return;
        }
        var box = victim.getBoundingBox().inflate(rules.allyRadius());
        for (Mob ally : victim.level().getEntitiesOfClass(Mob.class, box,
                other -> other != victim && allied(victim, other) && HumanoidBrain.managed(other))) {
            remember(ally, attacker, new Hostility(0, now, expiry, NO_MELEE_TICK), rules);
        }
    }

    private static void remember(LivingEntity victim, LivingEntity attacker, Hostility hit,
                                 HumanoidBrainConfig.ThreatRules rules) {
        // 同一攻击者再次出手：延长到期刻，伤害叠在衰减后的旧量上；同伴替我记的不带伤害，不改旧量。
        var remembered = attackers.computeIfAbsent(victim, ignored -> new WeakHashMap<>());
        Hostility old = remembered.get(attacker);
        if (old == null) {
            remembered.put(attacker, hit);
            return;
        }
        long expiry = Math.max(old.expiry(), hit.expiry());
        if (hit.damage() <= 0) {
            remembered.put(attacker, new Hostility(old.damage(), old.damagedAt(), expiry, old.lastMeleeTick()));
            return;
        }
        double kept = decayed(old, hit.damagedAt(), rules);
        long melee = Math.max(old.lastMeleeTick(), hit.lastMeleeTick());
        remembered.put(attacker, new Hostility(kept + hit.damage(), hit.damagedAt(), expiry, melee));
    }

    private static double decayed(Hostility hostility, long now, HumanoidBrainConfig.ThreatRules rules) {
        // 累计伤害从最近一次出手起在记忆秒数内线性衰减到 0。
        double elapsed = Math.max(0, now - hostility.damagedAt());
        return hostility.damage() * Math.max(0, 1 - elapsed / memoryTicks(rules));
    }

    private static long memoryTicks(HumanoidBrainConfig.ThreatRules rules) {
        // 记忆秒数折成 tick。
        return Math.round(rules.memorySeconds() * TICKS_PER_SECOND);
    }

    static double recentDamage(LivingEntity victim, LivingEntity attacker, HumanoidBrainConfig.ThreatRules rules) {
        // 攻击者近期对我造成的伤害，已按记忆秒数衰减；没打过我记 0。
        var remembered = attackers.get(victim);
        Hostility hostility = remembered == null ? null : remembered.get(attacker);
        if (hostility == null) {
            return 0;
        }
        return decayed(hostility, victim.level().getGameTime(), rules);
    }

    static boolean recentMelee(Mob victim, LivingEntity attacker, long windowMillis) {
        var remembered = attackers.get(victim);
        if (remembered == null) {
            return false;
        }
        var hostility = remembered.get(attacker);
        return hostility != null && hostility.lastMeleeTick() != NO_MELEE_TICK && windowMillis > 0
                && (victim.level().getGameTime() - hostility.lastMeleeTick()) * HumanoidArmGuard.MILLIS_PER_TICK
                <= windowMillis;
    }

    static Optional<LivingEntity> choose(Mob mob, HumanoidBrainConfig.ThreatRules rules,
                                         Optional<LivingEntity> previous) {
        // 只看事件登记与原版已有的敌意，按打分取一个；远于放下距离且看不见的不算。
        // 带主人的召唤物先换成主人再打分，召唤物打我、盯我都记在主人头上，主对手留在主人身上；
        // 召唤物贴到打击距离内、主人又走不到时才顺手打它。
        List<LivingEntity> candidates = candidates(mob, previous).stream()
                .distinct()
                .filter(candidate -> valid(mob, candidate))
                .filter(candidate -> HumanoidGuards.withinPost(mob, candidate))
                .toList();
        var sources = new LinkedHashMap<LivingEntity, List<LivingEntity>>();
        for (LivingEntity candidate : candidates) {
            LivingEntity principal = principal(mob, rules, candidate);
            if (noticed(mob, rules, principal)) {
                sources.computeIfAbsent(principal, ignored -> new ArrayList<>()).add(candidate);
            }
        }
        var scored = sources.entrySet().stream()
                .map(entry -> new HumanoidThreatScores.Candidate(entry.getKey(), entry.getValue()))
                .toList();
        LivingEntity held = previous.map(opponent -> principal(mob, rules, opponent)).orElse(null);
        return HumanoidThreatScores.best(mob, rules, scored, held)
                .map(chosen -> summonInReach(mob, chosen, candidates).orElse(chosen));
    }

    private static LivingEntity principal(Mob mob, HumanoidBrainConfig.ThreatRules rules, LivingEntity candidate) {
        // 召唤物换成它的最终主人；主人不在、是我的同伴或已经放下（远且看不见），就还是召唤物自己。
        LivingEntity owner = owner(candidate);
        if (owner == null || !valid(mob, owner) || allied(mob, owner) || !noticed(mob, rules, owner)) {
            return candidate;
        }
        return owner;
    }

    static LivingEntity owner(LivingEntity entity) {
        // 带主人的召唤物：恼鬼、驯服的狼和别的模组实现 OwnableEntity 的召唤物；没有主人的记 null。
        // 玩家造的铁傀儡原版只记「玩家造的」，不记是谁，算不到人头上。
        if (entity instanceof OwnableEntity ownable) {
            return ownable.getRootOwner();
        }
        return null;
    }

    private static boolean noticed(Mob mob, HumanoidBrainConfig.ThreatRules rules, LivingEntity candidate) {
        // 放下距离以内，或看得见；视线用原版每 tick 缓存的感知结果。
        double forget = rules.forgetDistance();
        return mob.distanceToSqr(candidate) <= forget * forget || mob.getSensing().hasLineOfSight(candidate);
    }

    private static Optional<LivingEntity> summonInReach(Mob mob, LivingEntity chosen, List<LivingEntity> candidates) {
        // 主人走得到就不理召唤物；走不到时，打击距离内最近的召唤物顺手打。
        List<LivingEntity> inReach = candidates.stream()
                .filter(candidate -> candidate != chosen && owner(candidate) != null)
                .filter(mob::isWithinMeleeAttackRange)
                .toList();
        if (inReach.isEmpty() || mob.isWithinMeleeAttackRange(chosen)
                || HumanoidThreatScores.reachable(mob, chosen)) {
            return Optional.empty();
        }
        return inReach.stream().min(Comparator.comparingDouble(mob::distanceToSqr));
    }

    private static List<LivingEntity> candidates(Mob mob, Optional<LivingEntity> previous) {
        // 上次的对手、我的目标、已经冲着我来的，以及村民看得见的出击对象。
        var candidates = new ArrayList<LivingEntity>();
        previous.ifPresent(candidates::add);
        addIfPresent(candidates, mob.getTarget());
        candidates.addAll(pressers(mob));
        if (mob instanceof Villager villager) {
            candidates.addAll(HumanoidSorties.candidates(villager, HumanoidBrain.config().villager()));
        }
        return candidates;
    }

    static List<LivingEntity> pressers(Mob mob) {
        // 已经冲着我来的：攻击我的、以我为目标的、受击记忆，以及村民的敌对与受击记忆。
        // 原版受击记忆不分敌我；记着的同伴是误伤，和登记攻击者时一样不算，同伴真以我为目标时由反查表取到。
        var pressers = new ArrayList<LivingEntity>();
        LivingEntity attacker = mob.getLastHurtByMob();
        if (attacker != null && !allied(mob, attacker)) {
            pressers.add(attacker);
        }
        var hunters = targetedBy.get(mob);
        if (hunters != null) {
            hunters.stream().filter(hunter -> hunter.getTarget() == mob).forEach(pressers::add);
        }
        var remembered = attackers.get(mob);
        if (remembered != null) {
            long now = mob.level().getGameTime();
            remembered.values().removeIf(hostility -> hostility.expiry() < now);
            pressers.addAll(remembered.keySet());
        }
        if (mob instanceof Villager villager) {
            var brain = villager.getBrain();
            brain.getMemory(MemoryModuleType.NEAREST_HOSTILE).ifPresent(pressers::add);
            brain.getMemory(MemoryModuleType.HURT_BY_ENTITY).ifPresent(pressers::add);
        }
        return pressers;
    }

    private static void addIfPresent(List<LivingEntity> candidates, LivingEntity candidate) {
        // 原版可空引用统一在这里过滤。
        if (candidate != null) {
            candidates.add(candidate);
        }
    }

    private static boolean valid(Mob mob, LivingEntity candidate) {
        // 同一世界的存活实体；旁观与创造模式玩家不算敌人，我自己魅惑着的生物也不算。
        if (candidate == mob || !candidate.isAlive() || candidate.isRemoved()
                || candidate.level() != mob.level() || candidate.isSpectator()
                || HumanoidCharmController.ownSubject(mob, candidate)) {
            return false;
        }
        return !(candidate instanceof Player player) || !player.isCreative();
    }

    static void forget(LivingEntity self, LivingEntity other) {
        // self 不再记 other 的仇：受击记忆、原版记的「谁打过我」，村民大脑里的敌对与受击记忆。
        var remembered = attackers.get(self);
        if (remembered != null) {
            remembered.remove(other);
        }
        if (self.getLastHurtByMob() == other) {
            self.setLastHurtByMob(null);
        }
        if (self instanceof Villager villager) {
            var brain = villager.getBrain();
            for (var memory : List.of(MemoryModuleType.NEAREST_HOSTILE, MemoryModuleType.HURT_BY_ENTITY)) {
                if (brain.getMemory(memory).filter(found -> found == other).isPresent()) {
                    brain.eraseMemory(memory);
                }
            }
        }
    }

    static boolean enemy(LivingEntity self, LivingEntity other) {
        // 我的对手、我的目标、以我为目标的，或在受击记忆里的。魅惑着的两边互不为敌。
        if (HumanoidCharmController.spares(self, other) || HumanoidCharmController.spares(other, self)) {
            return false;
        }
        if (self instanceof Mob mob && (mob.getTarget() == other
                || HumanoidBrain.opponent(mob).filter(opponent -> opponent == other).isPresent())) {
            return true;
        }
        if (other instanceof Mob hunter && hunter.getTarget() == self) {
            return true;
        }
        var remembered = attackers.get(self);
        Hostility hostility = remembered == null ? null : remembered.get(other);
        return hostility != null && hostility.expiry() >= self.level().getGameTime();
    }

    static boolean hittable(LivingEntity actor, LivingEntity target) {
        // 打击不伤同伴，除非对方正是我的敌人。
        if (HumanoidCharmController.controlled(actor)) {
            return target != actor && !HumanoidCharmController.protects(actor, target);
        }
        if (HumanoidCharmController.ownSubject(actor, target)) {
            return false;
        }
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

    static boolean pursued(Mob mob, LivingEntity threat) {
        // 双方速度都朝向撤退者时视为追击，撤退通道允许在仍有命中窗口时反击。
        var towardMob = mob.position().subtract(threat.position());
        Vec3 threatVelocity = threat.getDeltaMovement();
        return towardMob.lengthSqr() > 0
                && threatVelocity.dot(towardMob) > 0
                && mob.getDeltaMovement().dot(towardMob) > 0;
    }

    static int enemyCount(Mob mob, LivingEntity selected) {
        // 主动腿法只在当前威胁集合中只有选定目标时启用；远处可见敌人也计入。
        double radius = HumanoidBrain.config().threat().forgetDistance();
        return mob.level().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(radius),
                candidate -> valid(mob, candidate) && (candidate == selected || enemy(mob, candidate))).size();
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
        HumanoidThreatScores.clear();
        HumanoidSorties.clear();
    }
}
