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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;

/** 真正登录的旁观者，只用内存连接记录实际下发的声音、粒子包；女巫、唤魔者用例共用。 */
record SoundListener(ServerPlayer player, RecordingConnection connection) {
    private static final double POSITION_TOLERANCE = 0.13;
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    static SoundListener create(Entity source) {
        // 协商必要通道，观察者用旁观模式避免加入被观察生物的敌意情境。
        var channels = List.of("meridian:body_view", "meridian:gathering_response", "meridian:opening_response",
                "meridian_human:control", "meridian_progression:event",
                "kizuna_players_animation:play", "kizuna_players_animation:stop");
        var connection = new RecordingConnection(channels.stream().map(Identifier::parse).toList());
        String name = "listener_" + Integer.toUnsignedString(NEXT_ID.incrementAndGet(), 36);
        var profile = new GameProfile(UUID.randomUUID(), name);
        var level = (net.minecraft.server.level.ServerLevel) source.level();
        var player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
        TestPlayers.logIn(player, connection);
        player.setGameMode(GameType.SPECTATOR);
        player.setPos(source.position());
        player.setNoGravity(true /* noGravity */);
        connection.packets().clear();
        return new SoundListener(player, connection);
    }

    List<ClientboundSoundPacket> sounds(Entity source, String id) {
        // 过滤实际音源坐标，避免同批其他生物的声音导致假阳性。
        return connection.packets().stream().filter(ClientboundSoundPacket.class::isInstance)
                .map(ClientboundSoundPacket.class::cast)
                .filter(packet -> packet.getSound().value().location().toString().equals(id)
                        && near(packet.getX(), source.getX()) && near(packet.getY(), source.getY())
                        && near(packet.getZ(), source.getZ())).toList();
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
