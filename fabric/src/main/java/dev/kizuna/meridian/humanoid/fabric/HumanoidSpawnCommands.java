package dev.kizuna.meridian.humanoid.fabric;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidVariant;
import net.minecraft.command.argument.Vec3ArgumentType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;

final class HumanoidSpawnCommands {
    private static final int SUMMON_PERMISSION_LEVEL = 2;

    private HumanoidSpawnCommands() {
        // 命令只组装原版 Brigadier 节点，不维护第二套出生逻辑。
    }

    static LiteralArgumentBuilder<ServerCommandSource> command() {
        // 物种和档位都是 literal，自带补全并在解析期拒绝非法值。
        var summon = CommandManager.literal("summon")
                .requires(source -> source.hasPermissionLevel(SUMMON_PERMISSION_LEVEL));
        for (HumanoidSpecies species : HumanoidSpecies.values()) {
            var speciesNode = CommandManager.literal(new Identifier(species.entityId()).getPath());
            for (HumanoidVariant variant : HumanoidVariant.values()) {
                var selection = new HumanoidSpawns.Selection(species, variant);
                var variantNode = CommandManager.literal(variant.name().toLowerCase(Locale.ROOT))
                        .executes(context -> spawn(context.getSource(), selection, context.getSource().getPosition()))
                        .then(CommandManager.argument("pos", Vec3ArgumentType.vec3())
                                .executes(context -> spawn(context.getSource(), selection,
                                        Vec3ArgumentType.getVec3(context, "pos"))));
                speciesNode.then(variantNode);
            }
            summon.then(speciesNode);
        }
        return summon;
    }

    private static int spawn(ServerCommandSource source, HumanoidSpawns.Selection selection, Vec3d position) {
        // 和原版 summon 一样检查世界边界，未指定位置时使用命令源位置。
        var world = source.getWorld();
        if (!world.isInBuildLimit(BlockPos.ofFloored(position))) {
            source.sendError(Text.literal("生成位置超出世界边界"));
            return 0;
        }
        var entity = HumanoidSpawns.spawn(world, selection, position, SpawnReason.COMMAND);
        String variantName = "游炁";
        if (selection.variant() == HumanoidVariant.TRUE_QI) {
            variantName = "真炁";
        }
        Text feedback = Text.literal("已生成")
                .append(entity.getDisplayName())
                .append("（" + variantName + "）：" + entity.getUuidAsString());
        source.sendFeedback(() -> feedback, false /* broadcastToOps */);
        return 1;
    }
}
