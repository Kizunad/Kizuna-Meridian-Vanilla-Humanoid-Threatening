package dev.kizuna.meridian.humanoid.fabric;

import com.mojang.authlib.GameProfile;
import dev.kizuna.meridian.technique.fabric.testing.RecordingConnection;
import dev.kizuna.meridian.technique.fabric.testing.TestPlayers;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.level.GameType;

/** 真正登录的旁观者，只用内存连接记录实际下发声音包。 */
record WitchSoundListener(ServerPlayer player, RecordingConnection connection) {
    private static final double POSITION_TOLERANCE = 0.13;
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    static WitchSoundListener create(Witch witch) {
        // 协商必要通道，观察者用旁观模式避免加入女巫的敌意情境。
        var channels = List.of("meridian:body_view", "meridian:gathering_response", "meridian:opening_response",
                "meridian_human:control", "meridian_progression:event",
                "kizuna_players_animation:play", "kizuna_players_animation:stop");
        var connection = new RecordingConnection(channels.stream().map(Identifier::parse).toList());
        String name = "witch_" + Integer.toUnsignedString(NEXT_ID.incrementAndGet(), 36);
        var profile = new GameProfile(UUID.randomUUID(), name);
        var level = (net.minecraft.server.level.ServerLevel) witch.level();
        var player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
        TestPlayers.logIn(player, connection);
        player.setGameMode(GameType.SPECTATOR);
        player.setPos(witch.position());
        player.setNoGravity(true /* noGravity */);
        connection.packets().clear();
        return new WitchSoundListener(player, connection);
    }

    List<ClientboundSoundPacket> sounds(Witch witch, String id) {
        // 过滤实际音源坐标，避免同批其他女巫的声音导致假阳性。
        return connection.packets().stream().filter(ClientboundSoundPacket.class::isInstance)
                .map(ClientboundSoundPacket.class::cast)
                .filter(packet -> packet.getSound().value().location().toString().equals(id)
                        && near(packet.getX(), witch.getX()) && near(packet.getY(), witch.getY())
                        && near(packet.getZ(), witch.getZ())).toList();
    }

    private static boolean near(double received, double expected) {
        // 声音包坐标以 float 解码，GameTest 结构常在千万格外，此时 float 的最小步长约 1 格。
        return Math.abs(received - expected) < POSITION_TOLERANCE + Math.ulp((float) expected);
    }

    String describe() {
        // 失败时列出实际收到的全部声音包，区分没收到、收重和坐标不符。
        return connection.packets().stream().filter(ClientboundSoundPacket.class::isInstance)
                .map(ClientboundSoundPacket.class::cast)
                .map(packet -> packet.getSound().value().location() + "@" + packet.getSource() + "("
                        + packet.getX() + "," + packet.getY() + "," + packet.getZ() + ")")
                .toList() + " listener=" + player.position() + " removed=" + player.isRemoved()
                + " packets=" + connection.packets().size();
    }

    void close() {
        // 模拟正式客户端断线，让 Human 和网络宿主释放该观察者。
        connection.disconnectFromClient();
    }
}
