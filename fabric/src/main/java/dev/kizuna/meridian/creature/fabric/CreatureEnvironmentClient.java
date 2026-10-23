package dev.kizuna.meridian.creature.fabric;

import dev.kizuna.meridian.creature.server.RegionInventory;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/** Human 区域服务的 Fabric 字节协议客户端；不直接依赖 Human Fabric 实现类。 */
final class CreatureEnvironmentClient {
    private static final String ENTRYPOINT = "meridian_environment_host";
    private Method exchange;
    private Object endpoint;

    RegionSnapshot query(Entity entity) {
        var position = position(entity);
        var request = EnvironmentHostMessages.EnvironmentHostRequest.newBuilder()
                .setProtocolVersion(1).setRequestId(UUID.randomUUID().toString()).setQuery(position).build();
        var response = exchange(request);
        if (response.getUnbound()) {
            throw unavailable("Human 区域服务尚未绑定", MeridianMessages.FailureCode.ENVIRONMENT_UNAVAILABLE);
        }
        if (!response.hasRegion()) {
            if (response.hasFailure()) {
                throw unavailable("Human 区域查询失败", response.getFailure().getCode());
            }
            return new RegionSnapshot(RegionInventory.absent(), "");
        }
        var region = response.getRegion();
        var inventory = BodyProtobuf.fromInventory(region.getInventory());
        var entries = inventory.entries().stream()
                .map(qi -> new RegionInventory.Entry(qi.attribute().id().value(), qi.density(), qi.amount()))
                .toList();
        return new RegionSnapshot(RegionInventory.present(region.getConcentration(), entries), region.getSourceId());
    }

    EnvironmentHostMessages.EnvironmentHostResponse clockResponse() {
        var request = EnvironmentHostMessages.EnvironmentHostRequest.newBuilder()
                .setProtocolVersion(1).setRequestId(UUID.randomUUID().toString()).setClock(true).build();
        var response = exchange(request);
        if (response.getUnbound()) {
            throw unavailable("Human 世界时钟尚未绑定", MeridianMessages.FailureCode.ENVIRONMENT_UNAVAILABLE);
        }
        if (response.hasFailure()) {
            throw unavailable("Human 世界时钟查询失败", response.getFailure().getCode());
        }
        if (!response.hasNow() || response.getNow().getDomain().isBlank()
                || response.getNow().getEpoch().isBlank() || response.getNow().getElapsedMillis() < 0) {
            throw unavailable("Human 世界时钟响应无效", MeridianMessages.FailureCode.INVALID_REQUEST);
        }
        return response;
    }

    boolean release(EnvironmentHostMessages.RegionRelease release) {
        var request = EnvironmentHostMessages.EnvironmentHostRequest.newBuilder()
                .setProtocolVersion(1).setRequestId(UUID.randomUUID().toString()).setRelease(release).build();
        var response = exchange(request);
        if (response.getUnbound() || response.hasFailure()) {
            return false;
        }
        return response.hasReleased();
    }

    private EnvironmentHostMessages.RegionQuery position(Entity entity) {
        return EnvironmentHostMessages.RegionQuery.newBuilder()
                .setDimensionId(entity.getWorld().getRegistryKey().getValue().toString())
                .setBlockX(entity.getBlockX()).setBlockZ(entity.getBlockZ()).build();
    }

    private EnvironmentHostMessages.EnvironmentHostResponse exchange(
            EnvironmentHostMessages.EnvironmentHostRequest request) {
        byte[] payload;
        try {
            payload = invoke(request.toByteArray());
        } catch (RuntimeException failure) {
            if (failure instanceof Unavailable) {
                throw failure;
            }
            throw unavailable("Human 环境服务调用失败", MeridianMessages.FailureCode.ENVIRONMENT_UNAVAILABLE, failure);
        }
        try {
            var response = EnvironmentHostMessages.EnvironmentHostResponse.parseFrom(payload);
            if (!response.getRequestId().equals(request.getRequestId())) {
                throw new IllegalArgumentException("环境服务请求 ID 不一致");
            }
            return response;
        } catch (Exception failure) {
            throw unavailable("Human 环境服务响应无效", MeridianMessages.FailureCode.INTERNAL_ERROR, failure);
        }
    }

    private byte[] invoke(byte[] payload) {
        Method method = exchange;
        Object target = endpoint;
        if (method == null || target == null) {
            synchronized (this) {
                method = exchange;
                target = endpoint;
                if (method == null || target == null) {
                    List<Object> entrypoints = FabricLoader.getInstance().getEntrypoints(ENTRYPOINT, Object.class);
                    if (entrypoints.isEmpty()) {
                        throw unavailable("没有 Human 环境服务入口", MeridianMessages.FailureCode.ENVIRONMENT_UNAVAILABLE);
                    }
                    target = entrypoints.get(0);
                    try {
                        method = target.getClass().getMethod("exchange", byte[].class);
                    } catch (NoSuchMethodException failure) {
                        throw unavailable("Human 环境服务入口协议不匹配", MeridianMessages.FailureCode.INTERNAL_ERROR, failure);
                    }
                    endpoint = target;
                    exchange = method;
                }
            }
        }
        try {
            return (byte[]) method.invoke(target, (Object) payload);
        } catch (IllegalAccessException | InvocationTargetException failure) {
            Throwable cause = failure instanceof InvocationTargetException invocation
                    && invocation.getCause() != null ? invocation.getCause() : failure;
            throw unavailable("Human 环境服务入口拒绝调用", MeridianMessages.FailureCode.ENVIRONMENT_UNAVAILABLE, cause);
        }
    }

    private static Unavailable unavailable(String message, MeridianMessages.FailureCode code) {
        return new Unavailable(message, code, null);
    }

    private static Unavailable unavailable(String message, MeridianMessages.FailureCode code, Throwable cause) {
        return new Unavailable(message, code, cause);
    }

    record RegionSnapshot(RegionInventory inventory, String sourceId) {
        RegionSnapshot {
            if (inventory == null || sourceId == null) {
                throw new IllegalArgumentException("区域快照不能为空");
            }
        }
    }

    static final class Unavailable extends RuntimeException {
        private final MeridianMessages.FailureCode code;

        Unavailable(String message, MeridianMessages.FailureCode code, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        MeridianMessages.FailureCode code() {
            return code;
        }
    }
}
