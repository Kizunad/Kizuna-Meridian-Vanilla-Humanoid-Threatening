package dev.kizuna.meridian.humanoid.fabric;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidVariant;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.Vec3;

final class HumanoidSpawnCommands {
    private static final int SUMMON_PERMISSION_LEVEL = 2;

    private HumanoidSpawnCommands() {
        // 命令只组装原版 Brigadier 节点，不维护第二套出生逻辑。
    }

    static LiteralArgumentBuilder<CommandSourceStack> command() {
        // 物种和档位都是 literal，自带补全并在解析期拒绝非法值。
        var summon = Commands.literal("summon")
                .requires(source -> source.hasPermission(SUMMON_PERMISSION_LEVEL));
        for (HumanoidSpecies species : HumanoidSpecies.values()) {
            var speciesNode = Commands.literal(new ResourceLocation(species.entityId()).getPath());
            for (HumanoidVariant variant : HumanoidVariant.values()) {
                var selection = new HumanoidSpawns.Selection(species, variant);
                var variantNode = Commands.literal(variant.name().toLowerCase(Locale.ROOT))
                        .executes(context -> spawn(context.getSource(), selection, context.getSource().getPosition()))
                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                .executes(context -> spawn(context.getSource(), selection,
                                        Vec3Argument.getVec3(context, "pos"))));
                speciesNode.then(variantNode);
            }
            summon.then(speciesNode);
        }
        return summon;
    }

    private static int spawn(CommandSourceStack source, HumanoidSpawns.Selection selection, Vec3 position) {
        // 和原版 summon 一样检查世界边界，未指定位置时使用命令源位置。
        var world = source.getLevel();
        if (!world.isInWorldBounds(BlockPos.containing(position))) {
            source.sendFailure(Component.literal("生成位置超出世界边界"));
            return 0;
        }
        var entity = HumanoidSpawns.spawn(world, selection, position, MobSpawnType.COMMAND);
        String variantName = "游炁";
        if (selection.variant() == HumanoidVariant.TRUE_QI) {
            variantName = "真炁";
        }
        Component feedback = Component.literal("已生成")
                .append(entity.getDisplayName())
                .append("（" + variantName + "）：" + entity.getStringUUID());
        source.sendSuccess(() -> feedback, false /* broadcastToOps */);
        return 1;
    }
}
