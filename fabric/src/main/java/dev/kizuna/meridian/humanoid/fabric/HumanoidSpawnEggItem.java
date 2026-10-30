package dev.kizuna.meridian.humanoid.fabric;

import net.minecraft.entity.SpawnReason;
import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.stat.Stats;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** 分档调试蛋使用原版蛋模型，身份与出生选择固定在物品上。 */
public final class HumanoidSpawnEggItem extends Item {
    private final HumanoidSpawns.Selection selection;

    HumanoidSpawnEggItem(HumanoidSpawns.Selection selection) {
        // 独立物品不覆盖原版 SpawnEggItem 的实体到蛋映射。
        super(new Settings());
        this.selection = selection;
    }

    public HumanoidSpawns.Selection selection() {
        // 客户端颜色与服务端出生读取同一个不可变选择。
        return selection;
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        // 仅服务端生成实体；可替代空间用点击格，实体方块用点击面相邻格。
        var world = context.getWorld();
        if (world.isClient()) {
            return ActionResult.SUCCESS;
        }
        BlockPos clicked = context.getBlockPos();
        BlockPos spawnPos = clicked;
        if (!world.getBlockState(clicked).getCollisionShape(world, clicked).isEmpty()) {
            spawnPos = clicked.offset(context.getSide());
        }
        var player = context.getPlayer();
        if (player != null && !world.canPlayerModifyAt(player, spawnPos)) {
            return ActionResult.FAIL;
        }
        HumanoidSpawns.spawn((ServerWorld) world, selection, Vec3d.ofBottomCenter(spawnPos), SpawnReason.SPAWN_EGG);
        if (player == null || !player.getAbilities().creativeMode) {
            context.getStack().decrement(1);
        }
        if (player != null) {
            player.incrementStat(Stats.USED.getOrCreateStat(this));
        }
        return ActionResult.CONSUME;
    }
}
