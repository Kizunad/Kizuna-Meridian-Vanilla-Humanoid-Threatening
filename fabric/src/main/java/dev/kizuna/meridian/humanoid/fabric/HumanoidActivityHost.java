package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.protocol.ActivityHostProtobuf;
import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.ActivityHostMessages.ActivityHostRequest;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostRequest;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestFailure;
import dev.kizuna.meridian.server.BodyActivityHost;

import java.util.UUID;

/** 类人能力与附属进度入口，与健康宿主共用实体认领及唯一身体事务。 */
public final class HumanoidActivityHost implements BodyActivityHost {
    private final HumanoidHealthHost health = new HumanoidHealthHost();

    @Override
    public byte[] exchange(String callerId, byte[] payload) {
        // 健康查询统一完成主线程、实体类型、读档状态、身体时钟和环境释放处理。
        DefinitionId source = new DefinitionId(callerId);
        ActivityHostRequest request = ActivityHostProtobuf.decodeRequest(payload);
        var inspect = HealthHostRequest.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(request.getRequestId())
                .setSubjectId(request.getSubjectId())
                .setInspect(true /* inspect */)
                .build();
        byte[] inspectedBytes = health.exchange(HealthHostProtobuf.encodeRequest(inspect));
        HealthHostResponse inspected = HealthHostProtobuf.decodeResponse(inspectedBytes);
        if (!inspected.hasBody()) {
            return inspectedBytes;
        }
        var response = HealthHostResponse.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(request.getRequestId());
        if (!request.getActivity().getBodyCommand().getBodyId().equals(inspected.getBody().getId())) {
            return failure(response, FailureCode.ACCESS_DENIED);
        }
        BodyActivity activity = request.getActivity().getBodyCommand().getExecuteActivity();
        if (!onlyModifiersAndProgress(activity)) {
            return failure(response, FailureCode.ACCESS_DENIED);
        }
        return apply(source.value(), request, response);
    }

    private static boolean onlyModifiersAndProgress(BodyActivity activity) {
        // 只开放能力加撤和附属进度；其他活动字段与未知字段均不得借此入口提交。
        BodyActivity remaining = activity.toBuilder()
                .clearAddModifiers()
                .clearRemoveModifiers()
                .clearProgress()
                .build();
        return remaining.equals(BodyActivity.getDefaultInstance());
    }

    private static byte[] apply(String callerId, ActivityHostRequest request, HealthHostResponse.Builder response) {
        // 复用刚才被健康宿主认领的身体，失败按原协议返回，不构造替代状态。
        var entity = HumanoidFabric.findEntity(UUID.fromString(request.getSubjectId()));
        HumanoidBodyState state = ((HumanoidEntityAccess) entity).meridian$humanoidBody();
        try {
            var result = state.applyAddonActivity(callerId, request.getActivity());
            if (result.hasFailure()) {
                return failure(response, result.getFailure().getCode());
            }
            return HealthHostProtobuf.encodeResponse(response.setBody(state.snapshot()).build());
        } catch (HumanoidBodyState.HumanoidHealthFailure rejected) {
            return failure(response, rejected.code());
        } catch (RuntimeException rejected) {
            HumanoidFabric.report(entity, rejected);
            return failure(response, FailureCode.INTERNAL_ERROR);
        }
    }

    private static byte[] failure(HealthHostResponse.Builder response, FailureCode code) {
        // 保留核心公开失败码，调用方不得把拒绝解释成无身体。
        RequestFailure failure = RequestFailure.newBuilder()
                .setCode(code)
                .build();
        return HealthHostProtobuf.encodeResponse(response.setFailure(failure).build());
    }
}
