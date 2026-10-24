package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostRequest;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.server.BodyHealthHost;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.UUID;

/** Fabric 健康宿主入口；只认领七种生人实体类型。 */
public final class HumanoidHealthHost implements BodyHealthHost {
    @Override
    public byte[] exchange(byte[] payload) {
        // 解析和线程检查都在宿主边界完成，客户端或异步线程不能创建身体。
        HealthHostRequest request = HealthHostProtobuf.decodeRequest(payload);
        HealthHostResponse.Builder response = HealthHostResponse.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(request.getRequestId());
        if (!HumanoidFabric.isReady()) {
            return unbound(response);
        }
        if (!HumanoidFabric.server().isOnThread()) {
            return failure(response, MeridianMessages.FailureCode.ACCESS_DENIED);
        }
        LivingEntity entity = findSubject(request.getSubjectId());
        if (entity == null) {
            return unbound(response);
        }
        HumanoidSpeciesBinding binding = HumanoidSpeciesBinding.resolve(entity);
        if (binding == null) {
            return unbound(response);
        }
        HumanoidEntityAccess access = (HumanoidEntityAccess) entity;
        if (access.meridian$humanoidLoading()) {
            // 实体正文尚未读完时不允许附属抢先创建身体。
            return unbound(response);
        }
        return handleBoundEntity(request, response, entity, binding.species());
    }

    private static LivingEntity findSubject(String subjectId) {
        // 非 UUID 身份保持未绑定语义，查找范围仅限已加载的实体。
        UUID entityId;
        try {
            entityId = UUID.fromString(subjectId);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        return HumanoidFabric.findEntity(entityId);
    }

    private static byte[] handleBoundEntity(HealthHostRequest request, HealthHostResponse.Builder response,
                                            LivingEntity entity, HumanoidSpecies species) {
        // 绑定后先推进时间，再执行查询或健康效果；异常仍映射到公开失败码。
        try {
            BodyMessages.TimeCursor now = HumanoidFabric.now();
            HumanoidBodyState state = bodyState(entity, species, now);
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

    private static HumanoidBodyState bodyState(LivingEntity entity, HumanoidSpecies species,
                                               BodyMessages.TimeCursor now) {
        // 已绑定身体直接复用；缓存 NBT 优先恢复，只有没有快照时才查询区域并生成身体。
        HumanoidEntityAccess access = (HumanoidEntityAccess) entity;
        HumanoidBodyState existing = access.meridian$humanoidBody();
        if (existing != null) {
            return existing;
        }
        byte[] saved = access.meridian$humanoidSavedBody();
        UUID entityId = entity.getUuid();
        HumanoidBodyState created;
        if (saved != null && saved.length > 0) {
            var savedBody = new HumanoidBodyState.SavedBody(access.meridian$humanoidSavedOwner(), saved,
                    access.meridian$humanoidSavedReceipts(), access.meridian$humanoidSavedRegionSource());
            created = HumanoidBodyState.restore(entityId, savedBody, now);
        } else {
            var region = HumanoidFabric.region(entity);
            long worldSeed = ((ServerWorld) entity.getWorld()).getSeed();
            created = HumanoidBodyState.born(species, entityId, worldSeed, region, now);
        }
        access.meridian$humanoidBody(created);
        return created;
    }

    private static byte[] unbound(HealthHostResponse.Builder response) {
        // 未认领与暂未装配都使用协议约定的未绑定响应。
        return HealthHostProtobuf.encodeResponse(response.setUnbound(true /* unbound */).build());
    }

    private static byte[] failure(HealthHostResponse.Builder response, MeridianMessages.FailureCode code) {
        // 失败保留公开失败枚举；不把异常堆栈或策略实现返回给伤害/治疗附属。
        MeridianMessages.RequestFailure failure = MeridianMessages.RequestFailure.newBuilder()
                .setCode(code)
                .build();
        return HealthHostProtobuf.encodeResponse(response.setFailure(failure).build());
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
