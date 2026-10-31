package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostRequest;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.server.BodyHealthHost;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import java.util.Objects;
import java.util.UUID;

/** Fabric 健康宿主入口；只认领七种生人实体类型。 */
public final class HumanoidHealthHost implements BodyHealthHost {
    /** 调试用途，不进正式玩法：调试附属改写类人真元库存的入口；权限检查留在调用方命令层。 */
    public static MeridianMessages.FailureCode debugQi(LivingEntity entity, MeridianMessages.DebugQi request) {
        // 只在服务端主线程进入当前宿主的同一身体事务；未认领或正文未读完按无身体处理。
        Objects.requireNonNull(entity);
        Objects.requireNonNull(request);
        MinecraftServer server = HumanoidFabric.server();
        if (server == null || !server.isSameThread()) {
            return MeridianMessages.FailureCode.ACCESS_DENIED;
        }
        HumanoidSpeciesBinding binding = HumanoidSpeciesBinding.resolve(entity);
        if (binding == null) {
            return MeridianMessages.FailureCode.BODY_NOT_FOUND;
        }
        HumanoidEntityAccess access = (HumanoidEntityAccess) entity;
        if (!HumanoidFabric.isReady() || access.meridian$humanoidLoadFailed()) {
            return MeridianMessages.FailureCode.INTERNAL_ERROR;
        }
        if (access.meridian$humanoidLoading()) {
            return MeridianMessages.FailureCode.BODY_NOT_FOUND;
        }
        BodyMessages.TimeCursor now = HumanoidFabric.now();
        HumanoidBodyState state = bodyState(entity, binding.species(), now);
        try {
            state.debugQi(request, now);
        } catch (HumanoidBodyState.HumanoidHealthFailure rejected) {
            return rejected.code();
        }
        return MeridianMessages.FailureCode.FAILURE_CODE_UNSPECIFIED;
    }

    @Override
    public byte[] exchange(byte[] payload) {
        // 解析和线程检查都在宿主边界完成，客户端或异步线程不能创建身体。
        HealthHostRequest request = HealthHostProtobuf.decodeRequest(payload);
        HealthHostResponse.Builder response = HealthHostResponse.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(request.getRequestId());
        MinecraftServer server = HumanoidFabric.server();
        if (server == null) {
            return unbound(response);
        }
        if (!server.isSameThread()) {
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
        if (!HumanoidFabric.isReady() || access.meridian$humanoidLoadFailed()) {
            // 世界账本或此实体的身体读档失败都属于宿主错误，不能退回原版 HP。
            return failure(response, MeridianMessages.FailureCode.INTERNAL_ERROR);
        }
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
            if (request.hasExertion()) {
                state.exert(request.getExertion(), now);
                return HealthHostProtobuf.encodeResponse(response.setBody(state.snapshot()).build());
            }
            if (!request.hasEffect()) {
                return failure(response, MeridianMessages.FailureCode.INVALID_REQUEST);
            }
            boolean hadHealthReceipt = state.healthReceipt(request.getRequestId()).isPresent();
            boolean mayKill = mayKill(request);
            HumanoidFabric.enqueueRelease(entity, state.apply(request.getEffect(), now));
            if (mayKill && !hadHealthReceipt) {
                HumanoidTechniqueHost.damaged(entity);
            }
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

    private static boolean mayKill(HealthHostRequest request) {
        // 只有真实伤害或可信死亡确认可能让身体死亡；治疗和疲劳恢复不结束施放。
        var command = request.getEffect().getBodyCommand();
        return command.hasDamageBody() || command.hasConfirmBodyDeath();
    }

    static HumanoidBodyState bodyState(LivingEntity entity, HumanoidSpecies species,
                                               BodyMessages.TimeCursor now) {
        // 已绑定身体直接复用；缓存 NBT 优先恢复，只有没有快照时才查询区域并生成身体。
        HumanoidEntityAccess access = (HumanoidEntityAccess) entity;
        HumanoidBodyState existing = access.meridian$humanoidBody();
        if (existing != null) {
            return existing;
        }
        byte[] saved = access.meridian$humanoidSavedBody();
        UUID entityId = entity.getUUID();
        HumanoidBodyState created;
        if (saved != null && saved.length > 0) {
            var savedBody = new HumanoidBodyState.SavedBody(access.meridian$humanoidSavedOwner(), saved,
                    access.meridian$humanoidSavedReceipts(), access.meridian$humanoidSavedRegionSource());
            created = HumanoidBodyState.restore(entityId, savedBody, now, species);
        } else {
            var region = HumanoidFabric.region(entity);
            long worldSeed = ((ServerLevel) entity.level()).getSeed();
            var subject = new HumanoidBodyState.BirthSubject(species, entityId, null);
            created = HumanoidBodyState.born(subject, worldSeed, region, now);
        }
        access.meridian$humanoidBody(created);
        return created;
    }

    private static byte[] unbound(HealthHostResponse.Builder response) {
        // 无活动世界、未认领或实体正文尚在读取时使用未绑定响应。
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
        static HumanoidSpeciesBinding resolve(net.minecraft.world.entity.LivingEntity entity) {
            // 实体类型 ID 是唯一认领条件，未知来源的同类实体仍按类型进入同一规则。
            String id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            return dev.kizuna.meridian.humanoid.server.HumanoidSpecies.fromEntityId(id)
                    .map(HumanoidSpeciesBinding::new).orElse(null);
        }
    }
}
