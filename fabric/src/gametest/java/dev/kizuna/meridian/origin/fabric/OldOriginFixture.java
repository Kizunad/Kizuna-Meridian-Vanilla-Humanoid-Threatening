package dev.kizuna.meridian.origin.fabric;

import dev.kizuna.meridian.origin.server.OriginRecords;
import net.minecraft.server.level.ServerPlayer;

/** 用旧版已经选定的出身记录构造重登存档。 */
public final class OldOriginFixture {
    private OldOriginFixture() { }

    public static void save(ServerPlayer player, String id) {
        var bodies = new OriginBodies();
        var body = bodies.read(player).orElseThrow();
        var chosen = OriginRecords.assigned(OriginRecords.read(body), id, 1);
        var granted = OriginRecords.granted(chosen, OriginRecords.grantKey(id, 0));
        bodies.write(player, body, granted);
    }
}
