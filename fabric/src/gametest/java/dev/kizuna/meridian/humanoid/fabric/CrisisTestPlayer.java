package dev.kizuna.meridian.humanoid.fabric;

import com.mojang.authlib.GameProfile;
import dev.kizuna.meridian.technique.fabric.testing.RecordingConnection;
import dev.kizuna.meridian.technique.fabric.testing.TestPlayers;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * 招敌测试用的玩家：走原版进服入口，声明 Human 要求的界面通道（缺了 Human 会暂停身体，引擎就读不到），
 * 下线走真实断线入口，会触发引擎的下线写回。
 */
record CrisisTestPlayer(ServerPlayer player, RecordingConnection connection) implements AutoCloseable {
    private static final List<String> CHANNELS = List.of("meridian:body_view", "meridian:gathering_response",
            "meridian:opening_response", "meridian_human:control", "meridian_progression:event",
            "kizuna_players_animation:play", "kizuna_players_animation:stop");
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    static CrisisTestPlayer create(GameTestHelper context) {
        // 新玩家，名字带序号，方便日志里分辨。
        return create(context, UUID.randomUUID());
    }

    static CrisisTestPlayer create(GameTestHelper context, UUID id) {
        // 固定玩家身份时，出身候选仍由正式抽选规则和世界种子决定。
        String name = "crisis_" + Integer.toUnsignedString(NEXT_ID.incrementAndGet(), 36);
        return logIn(context, new GameProfile(id, name));
    }

    CrisisTestPlayer rejoin(GameTestHelper context) {
        // 同一个 UUID 和名字再进一次，就是退出重进；调用前先 close。
        return logIn(context, player.getGameProfile());
    }

    private static CrisisTestPlayer logIn(GameTestHelper context, GameProfile profile) {
        // 生存模式进服。
        var connection = new RecordingConnection(CHANNELS.stream().map(Identifier::parse).toList());
        var level = context.getLevel();
        var player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
        TestPlayers.logIn(player, connection);
        player.setGameMode(GameType.SURVIVAL);
        return new CrisisTestPlayer(player, connection);
    }

    @Override
    public void close() {
        // 真实断线：引擎写回记录，Human 撤销认领，玩家离开在线列表。
        connection.disconnectFromClient();
    }
}
