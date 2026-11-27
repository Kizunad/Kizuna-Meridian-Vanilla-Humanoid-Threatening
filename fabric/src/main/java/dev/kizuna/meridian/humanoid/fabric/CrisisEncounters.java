package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.CrisisRecord;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.FallSite;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Pending;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.RaidReceipt;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.RaidWatch;
import dev.kizuna.meridian.humanoid.server.CrisisOutcome;
import dev.kizuna.meridian.origin.fabric.Origins;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;

/** 服务端主线程上的在线快照与一次性订阅。订阅键由调用方用命名空间和稳定的业务身份构成。 */
public final class CrisisEncounters {
    private CrisisEncounters() { }

    public static Optional<CrisisRecord> current(ServerPlayer player) {
        return CrisisFabric.engine().record(player);
    }

    /** 凭玩家自己的行路签请求到场，找不到落点时可以持同一签再试。 */
    public static boolean requestRoad(ServerPlayer player, String party, long ticket) {
        return CrisisFabric.engine().requestRoad(player, party, ticket);
    }

    /** 玩家凭签离开本场追兵。 */
    public static boolean leaveRoad(ServerPlayer player, long ticket) {
        return CrisisFabric.engine().leaveRoad(player, ticket);
    }

    public static boolean partyExists(String id) {
        return CrisisFabric.parties().containsKey(id);
    }

    /** 本人主动挑战已在场的头目；胜逃负沿用轻场订阅。 */
    public static boolean challenge(ServerPlayer player, String party, net.minecraft.world.entity.Mob opponent) {
        return CrisisFabric.engine().challenge(player, party, opponent);
    }

    public static boolean feudUses(String key, String party) {
        return Origins.all().values().stream()
                .flatMap(origin -> CrisisOrigins.entries(origin).stream())
                .anyMatch(entry -> entry.key().equals(key) && entry.threat().party().equals(party));
    }

    public static void watch(ServerPlayer player, String key, RaidWatch watch) {
        current(player).filter(record -> !record.containsReceipts(key))
                .filter(record -> !watch.equals(record.getWatchesMap().get(key)))
                .ifPresent(record -> CrisisFabric.engine().update(player,
                        present -> present.toBuilder().putWatches(key, watch).build()));
    }

    public static void cancel(ServerPlayer player, String key) {
        current(player).filter(record -> record.containsWatches(key)).ifPresent(record ->
                CrisisFabric.engine().update(player, present -> present.toBuilder().removeWatches(key).build()));
    }

    public static void acknowledge(ServerPlayer player, String key, RaidReceipt receipt) {
        current(player).filter(record -> receipt.equals(record.getReceiptsMap().get(key)))
                .filter(record -> receipt.getPlayer().equals(player.getStringUUID()))
                .ifPresent(record -> CrisisFabric.engine().update(player,
                        present -> present.toBuilder().removeReceipts(key).build()));
    }

    static CrisisRecord retain(ServerPlayer player, Pending pending, CrisisRecord after,
                               CrisisOutcome.Ending ending, FallSite fall) {
        if (after.getWatchesCount() == 0) {
            return after;
        }
        var result = after.toBuilder();
        for (Map.Entry<String, RaidWatch> entry : after.getWatchesMap().entrySet()) {
            RaidWatch watch = entry.getValue();
            boolean road = pending.getRoadRequest() > 0;
            if (watch.getRoad() == road && watch.getParty().equals(pending.getParty())
                    && watch.getFeud().equals(pending.getFeud())) {
                var receipt = RaidReceipt.newBuilder()
                        .setRoad(road)
                        .setLeader(pending.getLeader())
                        .setRoadRequest(pending.getRoadRequest())
                        .setPlayer(player.getStringUUID())
                        .setEncounter(Math.addExact(after.getSettledCount(), after.getRoadCount()))
                        .setOnlineTicks(after.getOnlineTicks())
                        .setParty(pending.getParty())
                        .setFeud(pending.getFeud())
                        .setStage(pending.getStage())
                        .setOutcome(ending.name().toLowerCase(Locale.ROOT));
                if (ending == CrisisOutcome.Ending.WIN) {
                    receipt.setFall(fall);
                }
                result.removeWatches(entry.getKey());
                result.putReceipts(entry.getKey(), receipt.build());
            }
        }
        return result.build();
    }
}
