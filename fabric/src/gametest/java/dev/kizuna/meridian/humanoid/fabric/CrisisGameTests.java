package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Pending;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 招敌各个 GameTest 共用的小工具：拿引擎、现读身体、等记录接上、断言、逐 tick 等、执行指令、取补全、
 * 把玩家送到远处平地、叫来人、取记录里的来人、看招敌的几条进度。
 */
final class CrisisGameTests {
    static final int COMMAND_FAILED = 0;
    private static final int APART = 4096;
    private static final int LANE = 512;
    private static final int ARRIVAL_CHUNKS = 3;

    private CrisisGameTests() {
        // 只有静态工具。
    }

    static CrisisEngine engine() {
        // 接线时建好的那一个。
        return CrisisFabric.engine();
    }

    static BodySnapshot body(ServerPlayer player) {
        // 经身体宿主现读一份。
        return new CrisisBodies().read(player).orElseThrow();
    }

    static boolean attached(ServerPlayer player) {
        // 招敌记录接上了，出身附属也读完身体、发过进服事件，这时调试指令才定得了出身。
        return engine().record(player).isPresent() && engine().originKnown(player);
    }

    static void check(GameTestHelper context, boolean condition, String message) {
        // 不成立就让这条 GameTest 失败。
        context.assertTrue(condition, Component.literal(message));
    }

    static void endings(GameTestHelper context, ServerPlayer player, String... done) {
        // 招敌三条结局进度（不速之客、冤家路窄、了断）里，正好 done 这几条已完成，别的都还没完成。
        Set<String> wanted = Set.of(done);
        for (String name : List.of("repelled", "grudge_slain", "feud_ended")) {
            boolean expected = wanted.contains(name);
            check(context, advanced(player, name) == expected, "进度 " + name + " 完成与否应为 " + expected);
        }
    }

    static boolean advanced(ServerPlayer player, String name) {
        // 招敌那几条进度（cultivators/<名字>）完成了没有；进度文件没加载直接报错，不当作没完成。
        Identifier id = Identifier.fromNamespaceAndPath("meridian_humanoid_host", "cultivators/" + name);
        AdvancementHolder advancement = player.level().getServer().getAdvancements().get(id);
        if (advancement == null) {
            throw new AssertionError("进度没有加载：" + id);
        }
        return player.getAdvancements().getOrStartProgress(advancement).isDone();
    }

    static void waitUntil(GameTestHelper context, BooleanSupplier condition, Runnable then) {
        // 每 tick 看一次，成立了就接着做；一直不成立由 GameTest 时限判失败。
        if (condition.getAsBoolean()) {
            then.run();
            return;
        }
        context.runAfterDelay(1, () -> waitUntil(context, condition, then));
    }

    static void finish(GameTestHelper context, CrisisTestPlayer fixture) {
        // 测试玩家真实下线（会触发下线写回），免得留在后续测试里。
        fixture.close();
        context.succeed();
    }

    static int run(GameTestHelper context, String command) {
        // 用服务器的最高权限来源执行。
        return run(context.getLevel().getServer().createCommandSourceStack(), command);
    }

    static int run(CommandSourceStack source, String command) {
        // 26.2 的执行入口不返回结果，从来源的结果回调取；解析失败或执行失败都是 0。
        int[] result = {COMMAND_FAILED};
        source.getServer().getCommands().performPrefixedCommand(
                source.withCallback((success, value) -> result[0] = success ? value : COMMAND_FAILED), command);
        return result[0];
    }

    static Set<String> suggestions(CommandSourceStack source, String command) {
        // 原版补全的结果。
        var dispatcher = source.getServer().getCommands().getDispatcher();
        return dispatcher.getCompletionSuggestions(dispatcher.parse(command, source)).join().getList().stream()
                .map(suggestion -> suggestion.getText())
                .collect(Collectors.toSet());
    }

    static void standApart(GameTestHelper context, ServerPlayer player) {
        // 测试场地挨着别的用例，地表高低看排布，落脚点时有时无；把玩家送到远离场地的平地上，四周一样平。
        standApart(context, player, 0);
    }

    static void standApart(GameTestHelper context, ServerPlayer player, int lane) {
        // 同一批的用例场地只隔十来格，送到远处后再按道错开 512 格：各自的来人、旁观的生人互不相干，
        // 也不会被别的用例的玩家看见。要看「有没有人看见」的用例各占一道。
        moveTo(context, player, context.absolutePos(BlockPos.ZERO).offset(APART, 0, APART + lane * LANE));
    }

    static void moveTo(GameTestHelper context, ServerPlayer player, BlockPos column) {
        // 用原版 tp 送到这一列的地面上。那里和四周来人落脚的一圈（40 格内）区块先同步生成出来：不然高度图读到的是
        // 世界最低处，玩家会埋进基岩里；GameTest 的 tick 也跑得比后台生成区块快。测试玩家没有客户端回移动包，
        // 服务器不会把他的区块跟踪挪过去，那一圈过一会儿就卸了、来人再也找不到落脚点；tp 后替他挪，和真客户端一样。
        int chunkX = SectionPos.blockToSectionCoord(column.getX());
        int chunkZ = SectionPos.blockToSectionCoord(column.getZ());
        for (int dx = -ARRIVAL_CHUNKS; dx <= ARRIVAL_CHUNKS; dx++) {
            for (int dz = -ARRIVAL_CHUNKS; dz <= ARRIVAL_CHUNKS; dz++) {
                context.getLevel().getChunk(chunkX + dx, chunkZ + dz);
            }
        }
        BlockPos ground = context.getLevel().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
        check(context, context.getLevel().getBlockState(ground).isAir(), "落脚的地方不是空的：" + ground);
        run(context, "tp " + player.getScoreboardName() + " " + ground.getX() + " " + ground.getY() + " "
                + ground.getZ());
        player.level().getChunkSource().move(player);
    }

    static String strikeCommand(ServerPlayer player) {
        // 调试指令，目标是这名玩家。
        return "meridian humanoid crisis strike " + player.getScoreboardName();
    }

    static List<Mob> raiders(GameTestHelper context, ServerPlayer player) {
        // 记录里的来人，头目在最前；记录里的头目就是第一个。
        Pending pending = engine().record(player).orElseThrow().getPending();
        check(context, pending.getArrived() && pending.getLeader().equals(pending.getRaiders(0)), "记录：" + pending);
        return pending.getRaidersList().stream()
                .map(id -> (Mob) context.getLevel().getEntity(UUID.fromString(id)))
                .toList();
    }
}
