package dev.kizuna.meridian.creature.fabric;

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
public final class CreatureHealthHost implements BodyHealthHost {
    @Override
    public byte[] exchange(byte[] payload) {
        // 解析和线程检查都在宿主边界完成，客户端或异步线程不能创建身体。
        HealthHostRequest request = HealthHostProtobuf.decodeRequest(payload);
        HealthHostResponse.Builder response = HealthHostResponse.newBuilder().setProtocolVersion(1)
                .setRequestId(request.getRequestId());
        if (!CreatureFabric.isReady()) {
            return HealthHostProtobuf.encodeResponse(response.setUnbound(true).build());
        }
        if (!CreatureFabric.server().isOnThread()) {
            return failure(response, MeridianMessages.FailureCode.ACCESS_DENIED);
        }
        UUID entityId;
        try {
            entityId = UUID.fromString(request.getSubjectId());
        } catch (IllegalArgumentException invalid) {
            return HealthHostProtobuf.encodeResponse(response.setUnbound(true).build());
        }
        var entity = CreatureFabric.findEntity(entityId);
        if (entity == null) {
            return HealthHostProtobuf.encodeResponse(response.setUnbound(true).build());
        }
        CreatureSpeciesBinding binding = CreatureSpeciesBinding.resolve(entity);
        if (binding == null) {
            return HealthHostProtobuf.encodeResponse(response.setUnbound(true).build());
        }
        CreatureEntityAccess access = (CreatureEntityAccess) entity;
        if (access.meridian$creatureLoading()) {
            // 实体正文尚未读完时不允许附属抢先创建身体。
            return HealthHostProtobuf.encodeResponse(response.setUnbound(true).build());
        }
        try {
            BodyMessages.TimeCursor now = CreatureFabric.now();
            CreatureBodyState state = access.meridian$creatureBody();
            if (state == null) {
                byte[] saved = access.meridian$creatureSavedBody();
                if (saved != null && saved.length > 0) {
                    state = CreatureBodyState.restore(entityId, access.meridian$creatureSavedOwner(), saved,
                            access.meridian$creatureSavedReceipts(),
                            access.meridian$creatureSavedRegionSource(), now);
                } else {
                    var region = CreatureFabric.region(entity);
                    state = CreatureBodyState.born(binding.species(), entityId,
                            ((ServerWorld) entity.getWorld()).getSeed(), region.inventory(), region.sourceId(), now);
                }
                access.meridian$creatureBody(state);
            }
            CreatureFabric.enqueueRelease(entity, state.settle(now));
            if (request.hasInspect()) {
                return HealthHostProtobuf.encodeResponse(response.setBody(state.snapshot()).build());
            }
            if (!request.hasEffect()) {
                return failure(response, MeridianMessages.FailureCode.INVALID_REQUEST);
            }
            CreatureFabric.enqueueRelease(entity, state.apply(request.getEffect(), now));
            var receipt = state.healthReceipt(request.getRequestId());
            if (receipt.isPresent()) {
                response.setHealth(receipt.get());
            }
            return HealthHostProtobuf.encodeResponse(response.setBody(state.snapshot()).build());
        } catch (CreatureBodyState.CreatureHealthFailure rejected) {
            return failure(response, rejected.code());
        } catch (CreatureEnvironmentClient.Unavailable unavailable) {
            return failure(response, unavailable.code());
        } catch (RuntimeException failure) {
            CreatureFabric.report(entity, failure);
            return failure(response, MeridianMessages.FailureCode.INTERNAL_ERROR);
        }
    }

    private static byte[] failure(HealthHostResponse.Builder response, MeridianMessages.FailureCode code) {
        // 失败保留公开失败枚举；不把异常堆栈或策略实现返回给伤害/治疗附属。
        return HealthHostProtobuf.encodeResponse(response.setFailure(
                MeridianMessages.RequestFailure.newBuilder().setCode(code)).build());
    }

    private record CreatureSpeciesBinding(dev.kizuna.meridian.creature.server.CreatureSpecies species) {
        static CreatureSpeciesBinding resolve(net.minecraft.entity.LivingEntity entity) {
            // 实体类型 ID 是唯一认领条件，未知来源的同类实体仍按类型进入同一规则。
            String id = net.minecraft.registry.Registries.ENTITY_TYPE.getId(entity.getType()).toString();
            return dev.kizuna.meridian.creature.server.CreatureSpecies.fromEntityId(id)
                    .map(CreatureSpeciesBinding::new).orElse(null);
        }
    }
}
