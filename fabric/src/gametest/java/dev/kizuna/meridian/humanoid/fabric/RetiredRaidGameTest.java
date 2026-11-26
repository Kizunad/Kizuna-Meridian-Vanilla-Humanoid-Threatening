package dev.kizuna.meridian.humanoid.fabric;

import static dev.kizuna.meridian.humanoid.fabric.CrisisGameTests.*;

import com.google.protobuf.ByteString;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.CrisisRecord;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Feud;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Grudge;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Pending;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.RaidReceipt;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.RaidWatch;
import dev.kizuna.meridian.humanoid.server.CrisisRecords;
import dev.kizuna.meridian.origin.fabric.OldOriginFixture;
import dev.kizuna.meridian.origin.server.OriginRecords;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 同一角色读回旧版身体，分别覆盖在途和已到场的退役队伍。 */
public final class RetiredRaidGameTest {
    private static final String ORIGIN = "meridian_humanoid:orphan";
    private static final String PARTY = "meridian_humanoid:clan_foes";
    private static final String FEUD = ORIGIN + "#0";
    private static final String WATCH = "test:old";
    private static final String PREVIOUS = "test:previous";
    private static final int TIMEOUT = 300;

    private enum Phase { SCHEDULED, ARRIVED }

    @GameTest(maxTicks = TIMEOUT)
    public void scheduledRetiredPartyReleasesSlotOnRejoin(GameTestHelper context) {
        exercise(context, Phase.SCHEDULED);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void arrivedRetiredPartyKeepsSpoilsAndEscapeReceipt(GameTestHelper context) {
        exercise(context, Phase.ARRIVED);
    }

    private static void exercise(GameTestHelper context, Phase phase) {
        var first = CrisisTestPlayer.create(context);
        var player = first.player();
        waitUntil(context, () -> attached(player), () -> {
            // 停止在线招敌计时，再写旧快照，避免正常下线提前结算模拟的崩服现场。
            engine().disconnect(player);
            OldOriginFixture.save(player, ORIGIN);
            var carried = CrisisSpoils.encode(new ItemStack(Items.EMERALD), player.registryAccess());
            var record = oldRecord(player, phase, carried);
            new CrisisBodies().write(player, body(player), record);
            first.close();
            var second = first.rejoin(context);
            waitUntil(context, () -> attached(second.player()), () -> {
                verify(context, second.player(), record, phase);
                finish(context, second);
            });
        });
    }

    private static CrisisRecord oldRecord(ServerPlayer player, Phase phase, ByteString carried) {
        var leader = UUID.randomUUID().toString();
        var grudge = oldGrudge(leader, carried);
        var raid = Pending.newBuilder()
                .setParty(PARTY)
                .setFeud(FEUD);
        if (phase == Phase.ARRIVED) {
            raid.setArrived(true /* arrived */)
                    .setGrudgeLeads(true /* grudgeLeads */)
                    .setLeader(leader)
                    .setLeaderSpecies(grudge.getSpecies())
                    .setLeaderTier(grudge.getTier())
                    .addRaiders(leader);
        }
        var feud = Feud.newBuilder().setKey(FEUD).build();
        var watch = RaidWatch.newBuilder()
                .setFeud(FEUD)
                .setParty(PARTY)
                .build();
        var receipt = RaidReceipt.newBuilder()
                .setPlayer(player.getStringUUID())
                .setOutcome("win")
                .setEncounter(1)
                .build();
        return CrisisRecords.empty().toBuilder()
                .setSettledCount(1)
                .setGrudge(grudge)
                .addFeuds(feud)
                .setPending(raid)
                .putWatches(WATCH, watch)
                .putReceipts(PREVIOUS, receipt)
                .build();
    }

    private static Grudge oldGrudge(String leader, ByteString carried) {
        return Grudge.newBuilder()
                .setParty(PARTY)
                .setFeud(FEUD)
                .setSpecies("minecraft:pillager")
                .setTier("wandering_qi")
                .setEntity(leader)
                .setEncounters(1)
                .addCarried(carried)
                .build();
    }

    private static void verify(GameTestHelper context, ServerPlayer player, CrisisRecord old, Phase phase) {
        var snapshot = body(player);
        var origin = OriginRecords.read(snapshot);
        check(context, origin.getChosen().isEmpty() && origin.getDrawRound() == 1
                && origin.getOfferedCount() > 0 && !origin.getOfferedList().contains(ORIGIN),
                "灭门遗孤读档时没有重选");
        var record = CrisisRecords.read(snapshot);
        check(context, !record.hasPending() && !record.containsWatches(WATCH), "退役场次仍占位或留有订阅");
        check(context, record.getReceiptsOrThrow(PREVIOUS).equals(old.getReceiptsOrThrow(PREVIOUS)), "丢失旧回执");
        var grudge = record.getGrudge();
        var previousGrudge = old.getGrudge();
        check(context, grudge.getParty().equals(engine().rules().genericParty())
                && grudge.getEntity().equals(previousGrudge.getEntity())
                && grudge.getSpecies().equals(previousGrudge.getSpecies())
                && grudge.getTier().equals(previousGrudge.getTier())
                && grudge.getCarriedList().equals(previousGrudge.getCarriedList()), "记仇者或被夺物品丢失");
        if (phase == Phase.ARRIVED) {
            var receipt = record.getReceiptsOrThrow(WATCH);
            check(context, receipt.getOutcome().equals("escape") && receipt.getParty().equals(PARTY)
                    && record.getSettledCount() == old.getSettledCount() + 1, "到场旧队伍没有按逃局收场");
        } else {
            check(context, !record.containsReceipts(WATCH) && record.getSettledCount() == old.getSettledCount(),
                    "撤销排期产生了战果");
        }
    }
}
