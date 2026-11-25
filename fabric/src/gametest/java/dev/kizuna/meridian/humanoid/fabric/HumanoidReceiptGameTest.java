package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.RaidReceipt;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.RaidWatch;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.*;

public final class HumanoidReceiptGameTest {
    private static final String PARTY = "meridian_humanoid_test:feud";
    private static final String OTHER = "meridian_humanoid_test:orphan#0";
    private static final String KEY = "test:chapter";
    private static final int TIMEOUT = 600;
    private static final int REJOIN_DELAY = 3;
    private static final int WIN_LANE = 45;
    private static final int LEAVE_LANE = 46;

    @GameTest(maxTicks = TIMEOUT)
    public void samePartyDifferentFeudCannotClaimTheEncounter(GameTestHelper context) {
        var fixture = CrisisTestPlayer.create(context);
        ServerPlayer player = fixture.player();
        standApart(context, player, WIN_LANE);
        waitUntil(context, () -> attached(player) && run(context, strikeCommand(player) + " " + PARTY) > 0, () -> {
            CrisisEncounters.watch(player, KEY, watch(OTHER));
            String matching = "test:generic";
            CrisisEncounters.watch(player, matching, watch(""));
            var leader = raiders(context, player).getFirst();
            leader.die(player.damageSources().playerAttack(player));
            var settled = CrisisEncounters.current(player).orElseThrow();
            check(context, !settled.containsReceipts(KEY) && settled.containsWatches(KEY), "同队伍错仇家拿到回执");
            var receipt = settled.getReceiptsOrThrow(matching);
            check(context, receipt.getPlayer().equals(player.getStringUUID()) && receipt.getOutcome().equals("win")
                    && receipt.getEncounter() == settled.getSettledCount() && receipt.hasFall()
                    && receipt.getFall().getX() == leader.getX()
                    && receipt.getFall().getDimension().equals(player.level().dimension().identifier().toString()),
                    "胜场身份或落点不符：" + receipt);
            finish(context, fixture);
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void unacknowledgedEscapeReloadsWithTheSettledRaid(GameTestHelper context) {
        var first = CrisisTestPlayer.create(context);
        ServerPlayer player = first.player();
        standApart(context, player, LEAVE_LANE);
        waitUntil(context, () -> attached(player) && run(context, strikeCommand(player) + " " + PARTY) > 0, () -> {
            CrisisEncounters.watch(player, KEY, watch(""));
            first.close();
            context.runAfterDelay(REJOIN_DELAY, () -> {
                var second = first.rejoin(context);
                waitUntil(context, () -> attached(second.player()), () -> verifyReload(context, second));
            });
        });
    }

    private static void verifyReload(GameTestHelper context, CrisisTestPlayer fixture) {
        var player = fixture.player();
        var record = CrisisEncounters.current(player).orElseThrow();
        check(context, record.containsReceipts(KEY), "重进丢了待确认的收场回执");
        RaidReceipt receipt = record.getReceiptsOrThrow(KEY);
        check(context, !record.hasPending() && receipt.getOutcome().equals("escape") && !receipt.hasFall()
                && receipt.getOnlineTicks() == record.getSettledAt(), "下线逃局和回执不一致");
        var wrongEncounter = receipt.toBuilder().setEncounter(receipt.getEncounter() + 1).build();
        CrisisEncounters.acknowledge(player, KEY, wrongEncounter);
        check(context, CrisisEncounters.current(player).orElseThrow().containsReceipts(KEY), "错场次也确认了回执");
        CrisisEncounters.acknowledge(player, KEY, receipt);
        check(context, !CrisisEncounters.current(player).orElseThrow().containsReceipts(KEY), "确认后还留着回执");
        finish(context, fixture);
    }

    static RaidWatch watch(String feud) {
        return RaidWatch.newBuilder()
                .setParty(PARTY)
                .setFeud(feud)
                .build();
    }
}
