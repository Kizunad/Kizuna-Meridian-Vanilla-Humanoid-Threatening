package dev.kizuna.meridian.humanoid.fabric;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.fabric.MeridianChannels;
import dev.kizuna.meridian.fabric.ProtobufChannel;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathAction;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathControl;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringRequest;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringResponse;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/** 首次选定出身的同一条连接，从新学的卷发动运功，再由吐纳回执确认实际吸入。 */
final class RebornFirstBreath {
    private static final String FORM = "meridian_basic_techniques:later_breathing";
    private static final int CAST_TICKS = 5;
    private static final double INTAKE = 1.5;

    private RebornFirstBreath() {
        // 仅复用当前测试的玩家、连接和世界环境。
    }

    static void exercise(GameTestHelper context, CrisisTestPlayer fixture, Runnable finished) {
        // 发卷后直接使用玩家刚学到的式，无需管理员授法、读卷或开脉。
        var player = fixture.player();
        var technique = TechniqueReference.newBuilder().setId(FORM).setVersion(1).build();
        var cast = CastRequest.newBuilder()
                .setCastId(UUID.randomUUID().toString())
                .setBodyId(player.getStringUUID())
                .setTechnique(technique)
                .build();
        var outcome = TechniqueFabric.service().cast(player.getUUID(), cast);
        context.assertTrue(outcome.getReason().isEmpty(), "首次选择后发动失败 " + outcome);
        context.runAfterDelay(CAST_TICKS, () -> {
            var control = BreathControl.newBuilder()
                    .setAction(BreathAction.BEGIN_BREATHING)
                    .setAutomatic(true /* automatic */)
                    .setSequence(1)
                    .build();
            var request = GatheringRequest.newBuilder()
                    .setProtocolVersion(1)
                    .setBodyId(player.getStringUUID())
                    .setRequestId("first-breath")
                    .setBreath(control)
                    .build();
            player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(
                    MeridianChannels.GATHERING_REQUEST.payload(request.toByteArray())));
            CrisisGameTests.waitUntil(context, () -> absorbed(fixture), finished);
        });
    }

    private static boolean absorbed(CrisisTestPlayer fixture) {
        // 只接受真实服务端发出的吸入回执，既要正在自动吐纳，也要实际发生过接触。
        for (var packet : fixture.connection().packets().reversed()) {
            if (packet instanceof ClientboundCustomPayloadPacket custom
                    && custom.payload() instanceof ProtobufChannel.Payload data
                    && data.type().equals(MeridianChannels.GATHERING_RESPONSE.type())) {
                try {
                    var response = GatheringResponse.parseFrom(data.bytes());
                    if (response.hasFailure()) {
                        throw new AssertionError("新生吐纳被拒 " + response.getFailure());
                    }
                    var breath = response.getView().getBreathing();
                    return breath.getActive() && breath.getAutomatic()
                            && breath.getIntakeMultiplier() == INTAKE && breath.getAbsorbedQi() > 0;
                } catch (InvalidProtocolBufferException invalid) {
                    throw new AssertionError("无法读取吐纳回执", invalid);
                }
            }
        }
        return false;
    }
}
