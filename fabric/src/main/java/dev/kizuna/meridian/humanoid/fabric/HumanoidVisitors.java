package dev.kizuna.meridian.humanoid.fabric;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.Vec3;

/** 单人在约定地点赴会，场次归属及结束条件由发起方保存。 */
public final class HumanoidVisitors {
    private static final String VISITOR = "meridian_humanoid.visitor";

    private HumanoidVisitors() { }

    public static Optional<Villager> arrive(ServerLevel level, BlockPos feet, UUID visit, Component name) {
        // 同一次赴约可以在重登后重新绑定已经在场的来客。
        if (level.getEntity(visit) instanceof Villager existing && existing.entityTags().contains(VISITOR)) {
            return Optional.of(existing);
        }
        if (!level.hasChunkAt(feet) || !level.getBlockState(feet.below()).isSolid()
                || !level.getBlockState(feet).isAir() || !level.getBlockState(feet.above()).isAir()) {
            return Optional.empty();
        }
        var guest = EntityTypes.VILLAGER.create(level, EntitySpawnReason.EVENT);
        if (guest == null) {
            return Optional.empty();
        }
        guest.setUUID(visit);
        guest.setPos(Vec3.atBottomCenterOf(feet));
        if (!level.noCollision(guest)) {
            return Optional.empty();
        }
        guest.setCustomName(name);
        guest.setPersistenceRequired();
        guest.setNoAi(true /* noAi */);
        guest.addTag(VISITOR);
        if (level.addFreshEntity(guest)) {
            return Optional.of(guest);
        }
        return Optional.empty();
    }

    public static void depart(ServerLevel level, UUID visit) {
        if (level.getEntity(visit) instanceof Villager guest && guest.entityTags().contains(VISITOR)) {
            guest.discard();
        }
    }
}
