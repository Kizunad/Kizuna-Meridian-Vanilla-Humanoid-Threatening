package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidAssessment;
import dev.kizuna.meridian.humanoid.server.HumanoidBrainConfig;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;

/**
 * 村民主动出击：村民看得见、又在出击距离内的敌意对象算进选敌，评估划算就迎上去；
 * 评估不划算、对方又还没冲着他来的，当没看见，过一阵再评估。
 *
 * <p>只读原版感知每秒刷新一次的「看得见的活体」记忆，不另扫世界。</p>
 */
final class HumanoidSorties {
    /** 原版村民怕的那几种生物；数据包可以增删。 */
    static final TagKey<EntityType<?>> VILLAGE_HOSTILE = TagKey.create(Registries.ENTITY_TYPE,
            Identifier.fromNamespaceAndPath("meridian_humanoid", "village_hostile"));
    /** 村里声望到这个数及以下的玩家算敌意对象；和铁傀儡的门槛相同。 */
    static final int HOSTILE_REPUTATION = -100;
    private static final double TICKS_PER_SECOND = 20;
    private static final Map<Villager, Long> declinedUntil = new WeakHashMap<>();

    private HumanoidSorties() {
        // 只记哪些村民刚评估过不划算，实体卸载后弱引用自然释放。
    }

    static List<LivingEntity> candidates(Villager villager, HumanoidBrainConfig.VillagerRules rules) {
        // 刚评估过不划算的，这一阵不再为出击认对手；带着守村的不受这条限制。
        Long until = declinedUntil.get(villager);
        if (until != null && villager.level().getGameTime() < until) {
            return List.of();
        }
        return hostiles(villager, rules.sortieDistance());
    }

    private static List<LivingEntity> hostiles(Villager villager, double distance) {
        // 看得见、在这个距离内的敌意对象。
        return visible(villager)
                .find(entity -> villager.distanceToSqr(entity) <= distance * distance && hostile(villager, entity))
                .toList();
    }

    private static boolean hostile(Villager villager, LivingEntity entity) {
        // 村里恨透的玩家、原版村民怕的那几种、正在袭击里的袭击队伍成员、正盯着村里人的生物；同伴不算。
        if (HumanoidThreats.allied(villager, entity)) {
            return false;
        }
        if (entity instanceof Player player) {
            return villager.getPlayerReputation(player) <= HOSTILE_REPUTATION;
        }
        if (entity.getType().builtInRegistryHolder().is(VILLAGE_HOSTILE)
                || entity instanceof Raider raider && raider.hasActiveRaid()) {
            return true;
        }
        return entity instanceof Mob mob && mob.getTarget() != null
                && HumanoidThreats.faction(mob.getTarget()) == HumanoidThreats.Faction.VILLAGE;
    }

    static boolean overlooks(Villager villager, LivingEntity opponent, HumanoidBrainConfig.VillagerRules rules) {
        // 评估不划算时问这一句：对手还没冲着他来（没盯他、近期没打过他、也不在原版的近距离记忆里）就当没看见，
        // 并记下这一阵不再评估。带着守村的见了敌意对象不放过，照评估该躲就躲。
        // 召唤物冲着他来算在主人头上：恼鬼咬他，对手是唤魔者，不能当没看见。
        boolean pressed = HumanoidThreats.pressers(villager).stream()
                .anyMatch(presser -> presser == opponent || HumanoidThreats.owner(presser) == opponent);
        if (pressed || HumanoidDuties.guardsVillage(villager)) {
            return false;
        }
        long wait = Math.round(rules.sortieReconsiderSeconds() * TICKS_PER_SECOND);
        declinedUntil.put(villager, villager.level().getGameTime() + wait);
        return true;
    }

    static double handicap(Villager villager, HumanoidBrainConfig config) {
        // 人数上的劣势。对方人数：出击距离内的敌意对象，加上已经冲着他来的。
        // 我方帮手：同伴半径内看得见的铁傀儡，和手上有对手、正在对敌的村民。
        var enemies = new HashSet<>(hostiles(villager, config.villager().sortieDistance()));
        enemies.addAll(HumanoidThreats.pressers(villager));
        double radius = config.threat().allyRadius();
        long allies = visible(villager)
                .find(entity -> villager.distanceToSqr(entity) <= radius * radius && helping(entity))
                .count();
        return HumanoidAssessment.outnumbered(enemies.size(), (int) allies, config.villager().outnumberedWeight());
    }

    private static boolean helping(LivingEntity entity) {
        // 铁傀儡，或正在对敌的村民。
        if (entity instanceof IronGolem) {
            return true;
        }
        return entity instanceof Villager other && HumanoidBrain.hasOpponent(other)
                && HumanoidBrain.stance(other.getUUID())
                        .filter(stance -> stance == HumanoidAssessment.Stance.ENGAGE).isPresent();
    }

    private static NearestVisibleLivingEntities visible(Villager villager) {
        // 原版感知每秒刷新一次；还没刷新过就是空的。
        return villager.getBrain().getMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES)
                .orElse(NearestVisibleLivingEntities.empty());
    }

    static void clear() {
        // 服务器停止时丢弃全部登记。
        declinedUntil.clear();
    }
}
