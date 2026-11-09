package dev.kizuna.meridian.humanoid.fabric;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.human.protocol.pb.ControlMessages.ControlIntent;
import dev.kizuna.meridian.human.protocol.pb.ControlMessages.ControlRequest;
import dev.kizuna.meridian.human.server.PlayerControlDriver;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.server.TechniqueService;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** 用玩家真实身体和功法库选式，返回意图，不直接改玩家位置或扣费。 */
public final class HumanoidPlayerDriver implements PlayerControlDriver {
    private static final int MAX_REQUEST_BYTES = 4 * 1_024 * 1_024;
    private static final long TTL_TICKS = 4;
    private static final double FOLLOW_DISTANCE = 2;
    private static final double SPRINT_DISTANCE = 6;
    private static final double QUARTER_TURN = 90;
    private static final float SPRINT_FORWARD_MINIMUM = 0.8f;

    @Override
    public byte[] exchange(String caller, byte[] payload) {
        // 可信请求仍核对会话、实体、时钟和限长，绝不接收玩家自报身体。
        var server = HumanoidFabric.server();
        if (!CALLER.equals(caller) || server == null || !server.isSameThread()
                || payload.length == 0 || payload.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("玩家决策请求来源或大小无效");
        }
        try {
            ControlRequest request = ControlRequest.parseFrom(payload);
            var requestedSession = request.getSession();
            UUID subject = UUID.fromString(requestedSession.getPlayerUuid());
            var session = HumanoidCharmController.session(subject);
            if (session == null || !session.getSessionId().equals(requestedSession.getSessionId())
                    || !session.getOpponentUuid().equals(requestedSession.getOpponentUuid())
                    || request.getServerTick() != server.overworld().getGameTime()) {
                throw new IllegalArgumentException("玩家决策会话不匹配");
            }
            ServerPlayer player = server.getPlayerList().getPlayer(subject);
            if (player == null || !player.isAlive()) {
                throw new IllegalArgumentException("玩家决策主体不在线");
            }
            return decide(player, request).toByteArray();
        } catch (InvalidProtocolBufferException invalid) {
            throw new IllegalArgumentException("玩家决策请求协议无效", invalid);
        }
    }

    private static ControlIntent decide(ServerPlayer player, ControlRequest request) {
        // 没有施药者目标时跟随；已学修炼类不进入战斗选式，手持物保持原样。
        var session = request.getSession();
        long tick = request.getServerTick();
        var intent = ControlIntent.newBuilder()
                .setSessionId(session.getSessionId())
                .setSequence(request.getSequence())
                .setServerTick(tick)
                .setValidUntilTick(tick + TTL_TICKS)
                .setYaw(request.getYaw())
                .setPitch(request.getPitch());
        long stagger = TechniqueFabric.service().staggerRemainingMillis(player.getUUID(),
                TechniqueService.StaggerKind.CHARM);
        if (stagger > 0) {
            return intent.build();
        }
        LivingEntity opponent = HumanoidCharmController.opponent(player);
        LivingEntity destination = opponent;
        if (destination == null) {
            destination = HumanoidFabric.findEntity(UUID.fromString(session.getCasterUuid()));
        }
        if (destination == null || destination.level() != player.level()) {
            return intent.build();
        }
        aim(player, destination.getEyePosition(), intent);
        act(player, request, opponent, destination, intent);
        return intent.build();
    }

    private static void act(ServerPlayer player, ControlRequest request, LivingEntity opponent,
                            LivingEntity destination, ControlIntent.Builder intent) {
        // 先按当前身体挑选可支付的战斗式，已在近战距离就站定，否则沿有界路径接近。
        var body = BodyProtobuf.fromMessage(request.getBody());
        if (opponent != null) {
            HumanoidPlayerTechniques.choose(request, body, player, opponent).ifPresent(intent::setTechnique);
            if (player.isWithinEntityInteractionRange(opponent, 0) && player.hasLineOfSight(opponent)) {
                intent.setAttackUuid(opponent.getStringUUID());
                return;
            }
        }
        double distanceSquared = player.distanceToSqr(destination);
        if (distanceSquared > FOLLOW_DISTANCE * FOLLOW_DISTANCE) {
            HumanoidPlayerPath.move(player, destination.position(), body, intent);
            intent.setSprint(distanceSquared > SPRINT_DISTANCE * SPRINT_DISTANCE
                    && intent.getForward() >= SPRINT_FORWARD_MINIMUM);
        }
    }

    static void aim(ServerPlayer player, Vec3 target, ControlIntent.Builder intent) {
        // 视角使用原版坐标约定；移动执行器会按这个朝向转换水平走向。
        Vec3 offset = target.subtract(player.getEyePosition());
        float yaw = (float) (Math.toDegrees(Math.atan2(offset.z, offset.x)) - QUARTER_TURN);
        float pitch = (float) -Math.toDegrees(Math.atan2(offset.y, offset.horizontalDistance()));
        intent.setYaw(yaw);
        intent.setPitch(pitch);
    }
}
