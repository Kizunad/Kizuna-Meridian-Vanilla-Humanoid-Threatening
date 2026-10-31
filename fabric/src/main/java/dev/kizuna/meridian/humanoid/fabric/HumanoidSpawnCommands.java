package dev.kizuna.meridian.humanoid.fabric;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidSpeciesDefinition;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** /meridian humanoid spawn &lt;species&gt; [&lt;realm&gt;] [&lt;pos&gt;]；权限由注册点声明在 spawn 节点上。 */
final class HumanoidSpawnCommands {
    private static final String SPECIES = "species";
    private static final String POSITION = "pos";
    private static final DynamicCommandExceptionType UNKNOWN_SPECIES =
            new DynamicCommandExceptionType(id -> Component.literal("未知类人物种：" + id));

    private HumanoidSpawnCommands() {
        // 命令只组装原版 Brigadier 节点，不维护第二套出生逻辑。
    }

    static void attach(LiteralArgumentBuilder<CommandSourceStack> spawn) {
        // 物种是实体 ID，补全七种内置生人与全部登记物种；档位是 literal，自带补全并在解析期拒绝非法值。
        var species = Commands.argument(SPECIES, ResourceLocationArgument.id())
                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(speciesIds(), builder))
                .executes(context -> spawn(context, Optional.empty(), context.getSource().getPosition()))
                .then(position(Optional.empty()));
        for (HumanoidTier tier : HumanoidTier.values()) {
            species.then(Commands.literal(tier.id())
                    .executes(context -> spawn(context, Optional.of(tier), context.getSource().getPosition()))
                    .then(position(Optional.of(tier))));
        }
        spawn.then(species);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Coordinates> position(Optional<HumanoidTier> tier) {
        // 原版三维坐标参数，支持相对与局部坐标。
        return Commands.argument(POSITION, Vec3Argument.vec3())
                .executes(context -> spawn(context, tier, Vec3Argument.getVec3(context, POSITION)));
    }

    private static Stream<ResourceLocation> speciesIds() {
        // 补全按实体 ID 列出内置物种与运行时登记的物种（如训练假人）。
        return Stream.concat(HumanoidSpecies.values().stream(), HumanoidSpecies.registered().stream())
                .map(species -> new ResourceLocation(species.entityId()));
    }

    private static int spawn(CommandContext<CommandSourceStack> context, Optional<HumanoidTier> realm,
                             Vec3 position) throws CommandSyntaxException {
        // 读取物种，和原版 summon 一样检查世界边界；省略档位时登记物种用物种表默认档位，内置生人按自然出生抽档。
        CommandSourceStack source = context.getSource();
        ResourceLocation id = ResourceLocationArgument.getId(context, SPECIES);
        HumanoidSpecies species = HumanoidSpecies.fromEntityId(id.toString())
                .orElseThrow(() -> UNKNOWN_SPECIES.create(id));
        var world = source.getLevel();
        if (!world.isInWorldBounds(BlockPos.containing(position))) {
            source.sendFailure(Component.literal("生成位置超出世界边界"));
            return 0;
        }
        Optional<HumanoidTier> tier = realm.or(() -> species.definition().map(HumanoidSpeciesDefinition::defaultTier));
        LivingEntity entity = HumanoidSpawns.command(world, species, tier, position, source.getRotation().y);

        // 反馈实体、档位和 UUID；自然抽档的结果从刚出生的身体读回。
        HumanoidTier born = tier.orElseGet(() -> rolled(entity));
        Component feedback = Component.literal("已生成")
                .append(entity.getDisplayName())
                .append("（" + label(born) + "）：" + entity.getStringUUID());
        source.sendSuccess(() -> feedback, false /* broadcastToOps */);
        return 1;
    }

    private static HumanoidTier rolled(LivingEntity entity) {
        // 自然抽档在出生后由技法宿主准备时完成，档位按身体快照读回。
        return HumanoidTier.of(((HumanoidEntityAccess) entity).meridian$humanoidBody().snapshot());
    }

    private static String label(HumanoidTier tier) {
        // 反馈沿用原有中文档位名。
        return switch (tier) {
            case MORTAL -> "凡人";
            case WANDERING_QI -> "游炁";
            case TRUE_QI -> "真炁";
        };
    }
}
