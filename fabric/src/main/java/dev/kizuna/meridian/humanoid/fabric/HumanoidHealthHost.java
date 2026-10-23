package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostRequest;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.server.BodyHealthHost;
import net.minecraft.server.world.ServerWorld;

import java.util.UUID;

/** Fabric 健康宿主入口；只认领本批次七种实体类型。 */
public final class HumanoidHealthHost implements BodyHealthHost {
    @Override
    public byte[] exchange(byte[] payload) {
        // 解析和线程检查都在宿主边界完成，客户端或异步线程不能创建身体。
        HealthHostRequest request = HealthHostProtobuf.decodeRequest(payload);
        HealthHostResponse.Builder response = HealthHostResponse.newBuilder().setProtocolVersion(1)
                .setRequestId(request.getRequestId());
        if (!HumanoidFabric.isReady()) {
            return HealthHostProtobuf.encodeResponse(response.setUnbound(true).build());
        }
        if (!HumanoidFabric.server().isOnThread()) {
            return failure(response, MeridianMessages.FailureCode.ACCESS_DENIED);
        }
        UUID entityId;
        try {
            entityId = UUID.fromString(request.getSubjectId());
        } catch (IllegalArgumentException invalid) {
            return HealthHostProtobuf.encodeResponse(response.setUnbound(true).build());
        }
        var entity = HumanoidFabric.findEntity(entityId);
        if (entity == null) {
            return HealthHostProtobuf.encodeResponse(response.setUnbound(true).build());
        }
        HumanoidSpeciesBinding binding = HumanoidSpeciesBinding.resolve(entity);
        if (binding == null) {
            return HealthHostProtobuf.encodeResponse(response.setUnbound(true).build());
        }
        HumanoidEntityAccess access = (HumanoidEntityAccess) entity;
        if (access.meridian$humanoidLoading()) {
            // 实体正文尚未读完时不允许附属抢先创建身体。
            return HealthHostProtobuf.encodeResponse(response.setUnbound(true).build());
        }
        try {
            BodyMessages.TimeCursor now = HumanoidFabric.now();
            HumanoidBodyState state = access.meridian$humanoidBody();
            if (state == null) {
                byte[] saved = access.meridian$humanoidSavedBody();
                if (saved != null && saved.length > 0) {
                    state = HumanoidBodyState.restore(entityId, access.meridian$humanoidSavedOwner(), saved,
                            access.meridian$humanoidSavedReceipts(),
                            access.meridian$humanoidSavedRegionSource(), now);
                } else {
                    var region = HumanoidFabric.region(entity);
                    state = HumanoidBodyState.born(binding.species(), entityId,
                            ((ServerWorld) entity.getWorld()).getSeed(), region.inventory(), region.sourceId(), now);
                }
                access.meridian$humanoidBody(state);
            }
            HumanoidFabric.enqueueRelease(entity, state.settle(now));
            if (request.hasInspect()) {
                return HealthHostProtobuf.encodeResponse(response.setBody(state.snapshot()).build());
            }
            if (!request.hasEffect()) {
                return failure(response, MeridianMessages.FailureCode.INVALID_REQUEST);
            }
            HumanoidFabric.enqueueRelease(entity, state.apply(request.getEffect(), now));
            var receipt = state.healthReceipt(request.getRequestId());
            if (receipt.isPresent()) {
                response.setHealth(receipt.get());
            }
            return HealthHostProtobuf.encodeResponse(response.setBody(state.snapshot()).build());
        } catch (HumanoidBodyState.HumanoidHealthFailure rejected) {
            return failure(response, rejected.code());
        } catch (HumanoidEnvironmentClient.Unavailable unavailable) {
            return failure(response, unavailable.code());
        } catch (RuntimeException failure) {
            HumanoidFabric.report(entity, failure);
            return failure(response, MeridianMessages.FailureCode.INTERNAL_ERROR);
        }
    }

    private static byte[] failure(HealthHostResponse.Builder response, MeridianMessages.FailureCode code) {
        // 失败保留公开失败枚举；不把异常堆栈或策略实现返回给伤害/治疗附属。
        return HealthHostProtobuf.encodeResponse(response.setFailure(
                MeridianMessages.RequestFailure.newBuilder().setCode(code)).build());
    }

    private record HumanoidSpeciesBinding(dev.kizuna.meridian.humanoid.server.HumanoidSpecies species) {
        static HumanoidSpeciesBinding resolve(net.minecraft.entity.LivingEntity entity) {
            // 实体类型 ID 是唯一认领条件，未知来源的同类实体仍按类型进入同一规则。
            String id = net.minecraft.registry.Registries.ENTITY_TYPE.getId(entity.getType()).toString();
            return dev.kizuna.meridian.humanoid.server.HumanoidSpecies.fromEntityId(id)
                    .map(HumanoidSpeciesBinding::new).orElse(null);
        }
    }
}
