package dev.kizuna.meridian.humanoid.fabric;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.kizuna.meridian.fabric.MeridianCommands;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.CrisisRecord;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Feud;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Pending;
import dev.kizuna.meridian.humanoid.server.CrisisRules;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** /meridian humanoid crisis …：看招敌记录、立刻来人；调试指令，权限由注册点声明在 crisis 节点上。 */
final class CrisisCommands {
    private static final String PARTY = "party";

    private CrisisCommands() {
        // 指令只读写引擎里的记录，不另存状态。
    }

    static void register(CrisisEngine engine) {
        // crisis 挂在 humanoid 分支下；目标省略时是执行者，指定时用原版玩家选择器，全部能 Tab 补全。
        MeridianCommands.register(MeridianCommands.Branch.HUMANOID, "crisis", MeridianCommands.GAMEMASTER,
                (node, context) -> attach(node, engine));
    }

    private static void attach(LiteralArgumentBuilder<CommandSourceStack> crisis, CrisisEngine engine) {
        // status [targets]：风声、在线天数、排着的那一场、出身的仇家、记仇的人。
        var status = Commands.literal("status");
        MeridianCommands.optionalPlayers(status, (parent, targets) -> parent.executes(context ->
                status(context.getSource(), engine, targets.resolve(context))));
        crisis.then(status);
        // strike [targets [party]]：排着没来的立刻来人；没排的按队伍表（缺省通用）排一场立刻来；不看时辰。
        // 队伍表只接在指定目标的那支后面：省略目标时 strike <词> 按省略目标解析，玩家名会被当成队伍表；给自己指定写 @s。
        var strike = Commands.literal("strike");
        MeridianCommands.optionalPlayers(strike, (parent, targets) -> {
            parent.executes(context -> strike(context.getSource(), engine, targets.resolve(context), Optional.empty()));
            if (parent != strike) {
                parent.then(Commands.argument(PARTY, IdentifierArgument.id())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                CrisisFabric.parties().keySet(), builder))
                        .executes(context -> strike(context.getSource(), engine, targets.resolve(context),
                                Optional.of(IdentifierArgument.getId(context, PARTY).toString()))));
            }
        });
        crisis.then(strike);
        // omen [targets]：不等时刻，立刻给下一次征兆（传言或字条）；没排着就先照风声招来的那样排一场。
        var omen = Commands.literal("omen");
        MeridianCommands.optionalPlayers(omen, (parent, targets) -> parent.executes(context ->
                omen(context.getSource(), engine, targets.resolve(context))));
        crisis.then(omen);
    }

    private static int omen(CommandSourceStack source, CrisisEngine engine, Collection<ServerPlayer> players) {
        // 每名目标各给一次；给不了的说清原因。
        int given = 0;
        for (ServerPlayer player : players) {
            CrisisEngine.Omen result = engine.omen(player);
            String name = player.getScoreboardName();
            switch (result) {
                case RUMOR, NOTE -> {
                    String what = result == CrisisEngine.Omen.RUMOR ? "村民说了一句传言" : "留下一张字条";
                    source.sendSuccess(() -> Component.literal(name + "：" + what), true /* broadcastToOps */);
                    given++;
                }
                default -> source.sendFailure(Component.literal(name + "：" + omenFailure(result)));
            }
        }
        return given;
    }

    private static String omenFailure(CrisisEngine.Omen result) {
        // 给不了征兆的原因。
        return switch (result) {
            case NOT_ATTACHED -> "招敌记录还没接上";
            case ALREADY_ARRIVED -> "这一场已经来人了";
            case ALL_GIVEN -> "这一场的征兆已经给完了";
            case NO_VILLAGER -> "字条已经留过，身边又没有看得见你的村民";
            case NO_SPOT -> "床不在近处，身后也找不到放字条的地方";
            case RUMOR, NOTE -> throw new IllegalArgumentException("给了征兆不是失败");
        };
    }

    private static int strike(CommandSourceStack source, CrisisEngine engine, Collection<ServerPlayer> players,
                              Optional<String> party) {
        // 每名目标各来一拨；来不了的说清原因。
        int arrived = 0;
        for (ServerPlayer player : players) {
            CrisisEngine.Strike result = engine.strike(player, party);
            String name = player.getScoreboardName();
            if (result == CrisisEngine.Strike.ARRIVED) {
                source.sendSuccess(() -> Component.literal(name + "：来人了"), true /* broadcastToOps */);
                arrived++;
            } else {
                source.sendFailure(Component.literal(name + "：" + strikeFailure(result)));
            }
        }
        return arrived;
    }

    private static String strikeFailure(CrisisEngine.Strike result) {
        // 指令失败的原因。
        return switch (result) {
            case NOT_ATTACHED -> "招敌记录还没接上";
            case ALREADY_ARRIVED -> "这一场已经来人了";
            case OTHER_PARTY -> "已经排着另一张队伍表的一场";
            case UNKNOWN_PARTY -> "没有这张队伍表";
            case NO_SPOT -> "附近找不到看不见玩家的落脚点";
            case ARRIVED -> throw new IllegalArgumentException("来人了不是失败");
        };
    }

    private static int status(CommandSourceStack source, CrisisEngine engine, Collection<ServerPlayer> players) {
        // 每名目标一段；还没接上（身体没读到）或记录损坏的说明原因。
        int shown = 0;
        for (ServerPlayer player : players) {
            Optional<CrisisRecord> record = engine.record(player);
            if (record.isEmpty()) {
                source.sendFailure(Component.literal(player.getScoreboardName() + "：招敌记录还没接上"));
                continue;
            }
            Component text = Component.literal(describe(player.getScoreboardName(), record.get(), engine.rules()));
            source.sendSuccess(() -> text, false /* broadcastToOps */);
            shown++;
        }
        return shown;
    }

    static String describe(String name, CrisisRecord record, CrisisRules rules) {
        // 调试用的直白数字：天数按在线 24000 tick 一天折算。
        StringBuilder text = new StringBuilder(name).append(" 招敌：在线 ").append(days(record.getOnlineTicks()))
                .append(" 天，风声 ").append(String.format(Locale.ROOT, "%.1f", record.getHeat()))
                .append('/').append(rules.heat().threshold())
                .append("，已了结 ").append(record.getSettledCount()).append(" 场");
        if (record.getSettledCount() > 0) {
            text.append("（上一场在第 ").append(days(record.getSettledAt())).append(" 天）");
        }
        if (record.hasPending()) {
            text.append("\n排着的一场：").append(pending(record.getPending()));
        } else {
            text.append("\n没有排着的一场");
        }
        for (Feud feud : record.getFeudsList()) {
            text.append("\n仇家 ").append(feud.getKey()).append("：已打赢 ").append(feud.getStage()).append(" 拨，")
                    .append(feudState(feud));
        }
        if (record.hasGrudge()) {
            text.append("\n记仇的人：").append(record.getGrudge().getSpecies()).append(' ')
                    .append(record.getGrudge().getTier()).append("，交手 ").append(record.getGrudge().getEncounters())
                    .append(" 次，身上带着夺走的 ").append(record.getGrudge().getCarriedCount()).append(" 件");
        }
        return text.toString();
    }

    private static String pending(Pending pending) {
        // 队伍表、第几拨、征兆进度和来人时刻；来了以后写来了几个人。
        String head = pending.getParty() + "，第 " + (pending.getStage() + 1) + " 拨";
        if (pending.getArrived()) {
            return head + "，第 " + days(pending.getArrivedAt()) + " 天来了 " + pending.getRaidersCount() + " 人";
        }
        return head + "，征兆 " + pending.getOmensGiven() + "/" + pending.getOmenAtCount() + "，第 "
                + days(pending.getStrikeAt()) + " 天来人";
    }

    private static String feudState(Feud feud) {
        // 了断、还没开始计日、下一拨哪天到。
        if (feud.getFinished()) {
            return "已了断";
        }
        if (!feud.getStarted()) {
            return "还没开始计日";
        }
        return "下一拨第 " + days(feud.getDueAt()) + " 天到";
    }

    private static String days(long ticks) {
        // 保留两位小数的在线天数。
        return String.format(Locale.ROOT, "%.2f", ticks / (double) CrisisRules.DAY_TICKS);
    }
}
