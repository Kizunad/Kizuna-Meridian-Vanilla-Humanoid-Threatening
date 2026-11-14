package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.human.fabric.HumanLimbStrikes;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/** 类人生物出拳脚时交给 Human 共用打击执行器的宿主事实：敌我按类人敌意规则，声明目标就是施放时盯着的那个。 */
record HumanoidLimbHost(LivingEntity actor) implements HumanLimbStrikes.Host {
    @Override
    public boolean hittable(LivingEntity target) {
        // 不伤同伴，其余照类人敌意规则。
        return HumanoidThreats.hittable(actor, target);
    }

    @Override
    public Optional<LivingEntity> declaredTarget(EffectIntent effect, double reach) {
        // 施放时声明的目标优先命中：先认原版当前目标，再按 UUID 找；要在同一世界，
        // 距离从施放者碰撞箱边缘量，目标边缘进了范围就算，免得它挪一小步就丢。
        if (effect.getTargetId().isEmpty()) {
            return Optional.empty();
        }
        UUID targetId = UUID.fromString(effect.getTargetId());
        return currentTarget(targetId)
                .or(() -> Optional.ofNullable(HumanoidFabric.findEntity(targetId)))
                .filter(target -> target.level() == actor.level())
                .filter(target -> actor.getBoundingBox().inflate(reach).intersects(target.getBoundingBox()));
    }

    private Optional<LivingEntity> currentTarget(UUID targetId) {
        if (!(actor instanceof Mob mob)) {
            return Optional.empty();
        }
        return Optional.ofNullable(mob.getTarget()).filter(target -> target.getUUID().equals(targetId));
    }

    @Override
    public double bootArmor() {
        // 生物的腿法剂量不计靴子，和接执行器之前一样。
        return 0;
    }

    @Override
    public void wearBoots(int durabilityCost) {
        // 生物不磨鞋。
    }

    @Override
    public SoundSource soundSource() {
        return SoundSource.HOSTILE;
    }
}
