package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.Vec3;

/** 单人在约定地点赴会，场次归属及结束条件由发起方保存。 */
public final class HumanoidVisitors {
    private static final String VISITOR = "meridian_humanoid.visitor";

    private HumanoidVisitors() { }

    public static Optional<Villager> arrive(ServerLevel level, BlockPos feet, UUID visit, Component name) {
        return arrive(level, feet, visit, name, EntityTypes.VILLAGER);
    }

    /** 生成任一类人物种的来客，入世后照常认领身体；何时恢复行动由发起方决定。 */
    public static <T extends Mob> Optional<T> arrive(ServerLevel level, BlockPos feet, UUID visit, Component name,
                                                     EntityType<T> type) {
        // 同一次赴约可以在重登后重新绑定已经在场的来客。
        var present = level.getEntity(visit);
        if (present != null && present.entityTags().contains(VISITOR) && type.tryCast(present) != null) {
            return Optional.of(type.tryCast(present));
        }
        // 矮草、花这类可穿行的方块不挡落脚。
        if (!level.hasChunkAt(feet) || !level.getBlockState(feet.below()).isSolid()
                || !passable(level, feet) || !passable(level, feet.above())) {
            return Optional.empty();
        }
        var guest = type.create(level, EntitySpawnReason.EVENT);
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

    private static boolean passable(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    public static void depart(ServerLevel level, UUID visit) {
        if (level.getEntity(visit) instanceof Mob guest && guest.entityTags().contains(VISITOR)) {
            guest.discard();
        }
    }

    public static Mob swordsman(ServerLevel level, BlockPos feet, Component name) {
        var selection = new HumanoidSpawns.Selection(HumanoidSpecies.VINDICATOR, HumanoidTier.TRUE_QI);
        var guest = HumanoidSpawns.spawn(level, selection, Vec3.atBottomCenterOf(feet), EntitySpawnReason.EVENT);
        guest.setCustomName(name);
        guest.setPersistenceRequired();
        guest.setNoAi(true /* noAi */);
        return guest;
    }
}
