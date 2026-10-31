package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;

/** 分档调试蛋使用原版物种蛋模型，真炁档带附魔光效；身份与出生选择固定在物品上。 */
public final class HumanoidSpawnEggItem extends Item {
    private final HumanoidSpawns.Selection selection;

    HumanoidSpawnEggItem(Identifier id, HumanoidSpawns.Selection selection) {
        // 独立物品不覆盖原版 SpawnEggItem 的实体到蛋映射。
        super(properties(id, selection));
        this.selection = selection;
    }

    private static Properties properties(Identifier id, HumanoidSpawns.Selection selection) {
        // 26.2 物品属性须带注册键；原版蛋改为逐物种贴图、不能再提亮着色，真炁档改用附魔光效在创造栏区分。
        var properties = new Properties().setId(ResourceKey.create(Registries.ITEM, id));
        if (selection.variant() == HumanoidVariant.TRUE_QI) {
            properties.component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return properties;
    }

    public HumanoidSpawns.Selection selection() {
        // 服务端出生与测试读取同一个不可变选择。
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
        HumanoidSpawns.spawn((ServerLevel) world, selection, Vec3.atBottomCenterOf(spawnPos),
                EntitySpawnReason.SPAWN_ITEM_USE);
        if (player == null || !player.getAbilities().instabuild) {
            context.getItemInHand().shrink(1);
        }
        if (player != null) {
            player.awardStat(Stats.ITEM_USED.get(this));
        }
        return InteractionResult.CONSUME;
    }
}
