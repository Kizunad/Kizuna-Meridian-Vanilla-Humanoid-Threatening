package dev.kizuna.meridian.humanoid.fabric;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;

/** 分档调试蛋使用原版蛋模型，身份与出生选择固定在物品上。 */
public final class HumanoidSpawnEggItem extends Item {
    private final HumanoidSpawns.Selection selection;

    HumanoidSpawnEggItem(HumanoidSpawns.Selection selection) {
        // 独立物品不覆盖原版 SpawnEggItem 的实体到蛋映射。
        super(new Properties());
        this.selection = selection;
    }

    public HumanoidSpawns.Selection selection() {
        // 客户端颜色与服务端出生读取同一个不可变选择。
        return selection;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        // 仅服务端生成实体；可替代空间用点击格，实体方块用点击面相邻格。
        var world = context.getLevel();
        if (world.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        BlockPos clicked = context.getClickedPos();
        BlockPos spawnPos = clicked;
        if (!world.getBlockState(clicked).getCollisionShape(world, clicked).isEmpty()) {
            spawnPos = clicked.relative(context.getClickedFace());
        }
        var player = context.getPlayer();
        if (player != null && !world.mayInteract(player, spawnPos)) {
            return InteractionResult.FAIL;
        }
        HumanoidSpawns.spawn((ServerLevel) world, selection, Vec3.atBottomCenterOf(spawnPos), MobSpawnType.SPAWN_EGG);
        if (player == null || !player.getAbilities().instabuild) {
            context.getItemInHand().shrink(1);
        }
        if (player != null) {
            player.awardStat(Stats.ITEM_USED.get(this));
        }
        return InteractionResult.CONSUME;
    }
}
