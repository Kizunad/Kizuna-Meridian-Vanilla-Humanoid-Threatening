package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.CrisisRecord;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;

/** 读旧档时收尾已经退役的四张队伍表。 */
final class RetiredRaids {
    private static final Set<String> PARTIES = Set.of("meridian_humanoid:recipe_thief", "meridian_humanoid:captors",
            "meridian_humanoid:highwaymen", "meridian_humanoid:clan_foes");

    private RetiredRaids() { }

    static CrisisRecord restore(ServerPlayer player, CrisisRecord record, CrisisEndings endings, String genericParty) {
        // 已到场的按逃局交付回执；未到场的撤销排期，不产生战果。
        CrisisRecord settled = settlePending(player, record, endings);
        var obsolete = settled.getWatchesMap().entrySet().stream()
                .filter(entry -> PARTIES.contains(entry.getValue().getParty()))
                .map(Map.Entry::getKey)
                .toList();
        var grudge = settled.getGrudge();
        boolean rename = PARTIES.contains(grudge.getParty());
        if (obsolete.isEmpty() && !rename) {
            return settled;
        }
        var restored = settled.toBuilder();
        obsolete.forEach(restored::removeWatches);
        if (rename) {
            // 记仇者仍会带着被夺物品回来，称呼与重逢台词改用通用队伍。
            var returning = grudge.toBuilder()
                    .setParty(genericParty)
                    .clearFeud()
                    .build();
            restored.setGrudge(returning);
        }
        return restored.build();
    }

    private static CrisisRecord settlePending(ServerPlayer player, CrisisRecord record, CrisisEndings endings) {
        if (!PARTIES.contains(record.getPending().getParty())) {
            return record;
        }
        if (record.getPending().getArrived()) {
            return endings.leave(player, record);
        }
        return record.toBuilder().clearPending().build();
    }
}
