package dev.kizuna.meridian.humanoid.fabric;

import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.body;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.engine;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.finish;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.run;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.suggestions;
import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.waitUntil;

import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.CrisisRecord;
import dev.kizuna.meridian.humanoid.server.CrisisRecords;
import dev.kizuna.meridian.humanoid.server.CrisisRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 修为招敌第 1 步：记录跟着在线时间走并写进玩家身体，突破到真炁时风声涨，风声够了排上一场，
 * 状态指令能看、能补全。
 */
public final class HumanoidCrisisGameTest {
    private static final int ATTACH_TIMEOUT = 400;
    private static final double BREAKTHROUGH_HEAT = 60;

    @GameTest(maxTicks = ATTACH_TIMEOUT)
    public void recordFollowsOnlineTimeIntoTheBody(GameTestHelper context) {
        // 进服后身体读得到就接上；过一段时间在线 tick 变多，写回以后身体里的记录和内存里的一样。
        CrisisTestPlayer fixture = CrisisTestPlayer.create(context);
        ServerPlayer player = fixture.player();
        waitUntil(context, () -> engine().record(player).isPresent(), () -> {
            long attachedAt = engine().record(player).orElseThrow().getOnlineTicks();
            waitUntil(context, () -> online(player) >= attachedAt + 2 * CrisisEngine.STEP_TICKS, () -> {
                engine().flush(player);
                CrisisRecord stored = CrisisRecords.read(body(player));
                CrisisRecord memory = engine().record(player).orElseThrow();
                context.assertTrue(stored.equals(memory), Component.literal("身体里的招敌记录和内存不一致"));
                context.assertTrue(stored.getOnlineTicks() > attachedAt, Component.literal("在线时间没往前走"));
                finish(context, fixture);
            });
        });
    }

    @GameTest(maxTicks = ATTACH_TIMEOUT)
    public void breakthroughRaisesHeat(GameTestHelper context) {
        // 第一次看到身体只记下；用 Human 的境界指令突破到真炁，下一次看身体时风声涨一大截。
        CrisisTestPlayer fixture = CrisisTestPlayer.create(context);
        ServerPlayer player = fixture.player();
        waitUntil(context, () -> engine().record(player).map(CrisisRecord::getBodySeen).orElse(false), () -> {
            context.assertTrue(heat(player) == 0, Component.literal("第一次看到身体就涨了风声"));
            int result = run(context, "meridian body realm " + player.getScoreboardName() + " true_qi");
            context.assertTrue(result > 0, Component.literal("境界指令没有成功"));
            waitUntil(context, () -> heat(player) > 0, () -> {
                double expected = CrisisRules.defaults().heat().rise().trueQiBreakthrough();
                context.assertTrue(heat(player) == expected && expected == BREAKTHROUGH_HEAT,
                        Component.literal("突破后的风声是 " + heat(player)));
                finish(context, fixture);
            });
        });
    }

    @GameTest(maxTicks = ATTACH_TIMEOUT)
    public void enoughHeatSchedulesTheGenericParty(GameTestHelper context) {
        // 过了保护期的真炁玩家风声过线，下一步就排上通用队伍表的一场，并马上写进身体。
        CrisisTestPlayer fixture = CrisisTestPlayer.create(context);
        ServerPlayer player = fixture.player();
        waitUntil(context, () -> engine().record(player).isPresent(), () -> {
            engine().update(player, record -> record.toBuilder().setBodySeen(true).setTrueQi(true)
                    .setOpenedMeridians(8).setHeat(80).setHeatRaisedAt(5 * CrisisRules.DAY_TICKS)
                    .setHeatDecayedAt(5 * CrisisRules.DAY_TICKS).setOnlineTicks(5 * CrisisRules.DAY_TICKS).build());
            waitUntil(context, () -> CrisisRecords.read(body(player)).hasPending(), () -> {
                CrisisRecord stored = CrisisRecords.read(body(player));
                context.assertTrue(stored.getPending().getParty().equals("meridian_humanoid:covetous"),
                        Component.literal("排上的不是通用队伍表：" + stored.getPending().getParty()));
                int omens = stored.getPending().getOmenAtCount();
                context.assertTrue(omens >= 2 && omens <= 3, Component.literal("征兆次数 " + omens));
                finish(context, fixture);
            });
        });
    }

    @GameTest(maxTicks = ATTACH_TIMEOUT)
    public void statusCommandShowsAndCompletes(GameTestHelper context) {
        // 状态指令说出风声和有没有排着的一场；crisis 下能补全出 status。
        CrisisTestPlayer fixture = CrisisTestPlayer.create(context);
        ServerPlayer player = fixture.player();
        waitUntil(context, () -> engine().record(player).isPresent(), () -> {
            List<String> lines = new ArrayList<>();
            CommandSourceStack source = context.getLevel().getServer().createCommandSourceStack()
                    .withSource(new LineCollector(lines));
            int result = run(source, "meridian humanoid crisis status " + player.getScoreboardName());
            String text = String.join("\n", lines);
            context.assertTrue(result == 1 && text.contains("风声") && text.contains("没有排着的一场"),
                    Component.literal("状态指令输出：" + text));
            Set<String> suggestions = suggestions(source, "meridian humanoid crisis ");
            context.assertTrue(suggestions.contains("status"), Component.literal("补全：" + suggestions));
            finish(context, fixture);
        });
    }

    private static long online(ServerPlayer player) {
        // 内存里的在线 tick。
        return engine().record(player).map(CrisisRecord::getOnlineTicks).orElse(0L);
    }

    private static double heat(ServerPlayer player) {
        // 内存里的风声。
        return engine().record(player).map(CrisisRecord::getHeat).orElse(0.0);
    }

    /** 收下指令发给来源的每一行字。 */
    private static final class LineCollector implements net.minecraft.commands.CommandSource {
        private final List<String> lines;

        LineCollector(List<String> lines) {
            // 收到的行直接写进调用方给的表。
            this.lines = lines;
        }

        @Override
        public void sendSystemMessage(Component message) {
            // 指令的成功和失败消息都走这里。
            lines.add(message.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            // 要收成功消息。
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            // 也要收失败消息。
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            // 不广播给其他管理员。
            return false;
        }
    }
}
