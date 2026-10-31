package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidBodyGenerator;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidVariant;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollItems;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 注册、物品交互、命令解析与唯一身体出生的完整宿主回归。 */
public final class HumanoidSpawnGameTest implements FabricGameTest {
    private static final int EGG_COUNT = 14;
    private static final int INITIAL_STACK_SIZE = 2;
    private static final int POSITION = 2;
    private static final int UUID_SEARCH_LIMIT = 4096;
    private static final long NATURAL_UUID_PREFIX = 0x7891213a516a4456L;
    private static final String SUMMON = "meridian humanoid summon ";
    private static final String TECHNIQUE_PREFIX = "meridian_door_breaker:";

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void humanoidCreativeGroupContainsFourteenEggsSeparateFromScrolls(GameTestHelper context) {
        // 注册表、展示栏和搜索栏使用同一集合，原版刷怪蛋映射不能被覆盖。
        var world = context.getLevel();
        var display = new CreativeModeTab.ItemDisplayParameters(world.enabledFeatures(),
                true /* hasPermissions */, world.registryAccess());
        var group = BuiltInRegistries.CREATIVE_MODE_TAB.get(HumanoidSpawnEggs.GROUP_ID);
        group.buildContents(display);
        var eggs = HumanoidSpawnEggs.eggs();
        context.assertTrue(eggs.size() == EGG_COUNT, "类人蛋数量不是十四个");
        context.assertTrue(itemIds(group.getDisplayItems()).equals(eggs.keySet()), "类人栏展示内容错误");
        context.assertTrue(itemIds(group.getSearchTabDisplayItems()).equals(eggs.keySet()), "类人栏搜索内容错误");
        var techniqueGroup = TechniqueScrollItems.group();
        techniqueGroup.buildContents(display);
        context.assertTrue(itemIds(techniqueGroup.getDisplayItems()).stream().noneMatch(eggs::containsKey),
                "类人蛋混入功法栏");
        context.assertTrue(SpawnEggItem.byId(EntityType.VINDICATOR) == Items.VINDICATOR_SPAWN_EGG,
                "分档蛋覆盖了原版卫道士刷怪蛋");
        context.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void eggsSpawnBothVindicatorRealmsAndInspectTheirLearnedVolumes(GameTestHelper context) {
        // 原版 ItemStack 右键入口生成，验证数量、装备、档位及真实 inspect 反馈。
        var world = context.getLevel();
        BlockPos floor = context.absolutePos(new BlockPos(POSITION, 1, POSITION));
        world.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        var player = context.makeMockSurvivalPlayer();
        for (HumanoidVariant variant : HumanoidVariant.values()) {
            var selection = new HumanoidSpawns.Selection(HumanoidSpecies.VINDICATOR, variant);
            var egg = HumanoidSpawnEggs.eggs().get(new ResourceLocation(HumanoidSpawnEggs.NAMESPACE, selection.path()));
            var stack = new ItemStack(egg, INITIAL_STACK_SIZE);
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            var hit = new BlockHitResult(Vec3.atCenterOf(floor), Direction.UP, floor, false /* insideBlock */);
            context.assertTrue(stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit))
                    == InteractionResult.CONSUME, "分档蛋交互失败");
            var spawned = world.getEntitiesOfClass(Vindicator.class, new AABB(floor.above()), entity -> true);
            context.assertTrue(spawned.size() == 1 && stack.getCount() == INITIAL_STACK_SIZE - 1,
                    "分档蛋没有恰好生成一个实体并消耗一份");
            var mob = spawned.get(0);
            try {
                assertSpawn(context, mob, variant);
            } finally {
                mob.discard();
            }
        }
        context.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void summonCommandSpawnsBothRealmsWithDefaultAndRelativePosition(GameTestHelper context) {
        // 两档均从正式命令树执行，并核对成功反馈和相对坐标。
        var output = new RecordedOutput();
        var source = source(context).withSource(output);
        var world = context.getLevel();
        var commands = world.getServer().getCommands();
        for (HumanoidVariant variant : HumanoidVariant.values()) {
            String baseCommand = SUMMON + "vindicator " + variant.name().toLowerCase(Locale.ROOT);
            Vec3 sourcePosition = source.getPosition();
            String command;
            Vec3 expected;
            if (variant == HumanoidVariant.TRUE_QI) {
                command = baseCommand + " ~1 ~ ~1";
                expected = sourcePosition.add(1, 0, 1);
            } else {
                command = baseCommand;
                expected = sourcePosition;
            }
            context.assertTrue(commands.performPrefixedCommand(source, command) == 1, "summon 命令生成失败");
            String feedback = output.messages.get(output.messages.size() - 1);
            String uuid = feedback.substring(feedback.lastIndexOf('：') + 1);
            var mob = (Vindicator) world.getEntity(UUID.fromString(uuid));
            try {
                context.assertTrue(mob != null && mob.position().equals(expected), "命令位置或反馈 UUID 错误");
                context.assertTrue(feedback.contains("已生成"), "命令缺少成功反馈");
                assertSpawn(context, mob, variant);
            } finally {
                if (mob != null) {
                    mob.discard();
                }
            }
        }
        context.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void naturalBirthStillRollsRealmAndPreservesRandomSequence(GameTestHelper context) {
        // 从真实区域寻找两个可复现 UUID，逐项比较抽档后的原始生成器库存。
        var world = context.getLevel();
        long seed = world.getSeed();
        for (HumanoidVariant desired : HumanoidVariant.values()) {
            var mob = EntityType.VINDICATOR.create(world);
            Vec3 position = source(context).getPosition();
            mob.moveTo(position.x, position.y, position.z, 0, 0);
            var region = HumanoidFabric.region(mob);
            UUID id = naturalId(seed, region, desired);
            mob.setUUID(id);
            mob.setNoAi(true /* aiDisabled */);
            context.assertTrue(world.addFreshEntity(mob), "自然生成测试实体 UUID 冲突");
            try {
                var now = HumanoidFabric.now();
                var random = HumanoidBodyGenerator.randomFor(seed, id);
                var generator = HumanoidFabric.generator();
                var rolled = generator.rollVariant(HumanoidSpecies.VINDICATOR, region.inventory(), random);
                var request = new HumanoidBodyGenerator.GenerationRequest(
                        HumanoidSpecies.VINDICATOR, rolled, id, region.inventory(), now);
                var expected = generator.generate(request, random);
                var actual = HumanoidHealthHost.bodyState(mob, HumanoidSpecies.VINDICATOR, now).snapshot();
                context.assertTrue(actual.getRealm().name().equals(desired.name()), "自然生成失去随机分档");
                context.assertTrue(actual.getMeridianStatesList().equals(expected.getMeridianStatesList()),
                        "自然生成的随机序列或经脉库存发生改变");
            } finally {
                mob.discard();
            }
        }
        context.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void commandsCompleteSpeciesRealmsPositionsItemsAndRejectInvalidInput(GameTestHelper context) {
        // 补全通过真实 dispatcher 查询；非法物种、档位和无权限输入都必须报错。
        var source = source(context);
        context.assertTrue(suggestions(source, SUMMON).equals(Set.of(
                "villager", "wandering_trader", "pillager", "vindicator", "evoker", "witch", "illusioner")),
                "物种补全不完整");
        context.assertTrue(suggestions(source, SUMMON + "vindicator ").equals(Set.of("wandering_qi", "true_qi")),
                "档位补全错误");
        context.assertTrue(suggestions(source, SUMMON + "vindicator true_qi ").contains("~ ~ ~"), "坐标没有补全");
        context.assertTrue(suggestions(source, "meridian humanoid inspect ").contains("@e"), "inspect 缺原版目标补全");
        context.assertTrue(suggestions(source, "give @s meridian_humanoid:").size() == EGG_COUNT, "give 未补全十四个蛋");
        var output = new RecordedOutput();
        var commands = context.getLevel().getServer().getCommands();
        var observed = source.withSource(output);
        for (String command : List.of(SUMMON + "zombie true_qi", SUMMON + "vindicator invalid")) {
            output.messages.clear();
            context.assertTrue(commands.performPrefixedCommand(observed, command) == 0 && !output.messages.isEmpty(),
                    "非法命令没有错误反馈");
        }
        output.messages.clear();
        context.assertTrue(commands.performPrefixedCommand(observed.withPermission(1), SUMMON + "vindicator true_qi") == 0
                && !output.messages.isEmpty(), "低权限命令没有拒绝");
        context.succeed();
    }

    private static void assertSpawn(GameTestHelper context, Vindicator mob, HumanoidVariant variant) {
        // 只读 inspect 必须立即可用；装备和自动学卷来自相同出生后的正式路径。
        var snapshot = ((HumanoidEntityAccess) mob).meridian$humanoidBody().snapshot();
        context.assertTrue(snapshot.getRealm().name().equals(variant.name()), "指定档位未用于首次身体创建");
        context.assertTrue(mob.getMainHandItem().is(Items.IRON_AXE), "原版卫道士初始化丢失铁斧");
        var output = new RecordedOutput();
        var commands = context.getLevel().getServer().getCommands();
        context.assertTrue(commands.performPrefixedCommand(source(context).withSource(output),
                "meridian humanoid inspect " + mob.getStringUUID()) == 1, "inspect 未找到已生成身体");
        context.assertTrue(output.messages.stream().anyMatch(message -> message.contains("档位=" + variant.name())),
                "inspect 档位反馈错误");
        var known = TechniqueFabric.service().known(mob.getUUID()).stream()
                .map(entry -> entry.technique().getId())
                .collect(Collectors.toSet());
        context.assertTrue(known.containsAll(Set.of(TECHNIQUE_PREFIX + "knock", TECHNIQUE_PREFIX + "cleave",
                TECHNIQUE_PREFIX + "ram")), "卫道士未自动学会上卷");
        if (snapshot.getRealm() == BodyMessages.Realm.TRUE_QI) {
            context.assertTrue(known.containsAll(Set.of(TECHNIQUE_PREFIX + "hook", TECHNIQUE_PREFIX + "sweep",
                    TECHNIQUE_PREFIX + "close")), "真炁卫道士未自动学会中卷");
        }
    }

    private static UUID naturalId(long seed, HumanoidEnvironmentClient.RegionSnapshot region,
                                   HumanoidVariant desired) {
        // 搜索固定 UUID 序列而非概率断言，避免小样本随机失败。
        var generator = HumanoidFabric.generator();
        for (int index = 0; index < UUID_SEARCH_LIMIT; index++) {
            var id = new UUID(NATURAL_UUID_PREFIX, index);
            var random = HumanoidBodyGenerator.randomFor(seed, id);
            if (generator.rollVariant(HumanoidSpecies.VINDICATOR, region.inventory(), random) == desired) {
                return id;
            }
        }
        throw new AssertionError("测试区域无法抽取指定自然档位：" + desired);
    }

    private static CommandSourceStack source(GameTestHelper context) {
        // 命令源固定在本测试区域，默认位置与相对坐标均不依赖世界出生点。
        var world = context.getLevel();
        Vec3 position = Vec3.atBottomCenterOf(context.absolutePos(new BlockPos(POSITION, POSITION, POSITION)));
        return world.getServer().createCommandSourceStack().withLevel(world).withPosition(position);
    }

    private static Set<String> suggestions(CommandSourceStack source, String command) {
        // 仅观察原版补全结果，不从实现枚举伪造命令可发现性。
        var dispatcher = source.getServer().getCommands().getDispatcher();
        return dispatcher.getCompletionSuggestions(dispatcher.parse(command, source))
                .join()
                .getList()
                .stream()
                .map(suggestion -> suggestion.getText())
                .collect(Collectors.toSet());
    }

    private static Set<ResourceLocation> itemIds(Iterable<ItemStack> stacks) {
        // 两种创造列表按注册身份比较，不依赖显示顺序。
        var ids = new HashSet<ResourceLocation>();
        stacks.forEach(stack -> ids.add(BuiltInRegistries.ITEM.getKey(stack.getItem())));
        return ids;
    }

    private static final class RecordedOutput implements CommandSource {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component message) {
            // 原版命令输出按最终文本记录，包含成功反馈和解析失败提示。
            messages.add(message.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            // 接收成功反馈以验证实体身份和档位。
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            // 接收错误输出，非法参数不能静默失败。
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            // 测试输出只保存在观察器里。
            return false;
        }
    }
}
