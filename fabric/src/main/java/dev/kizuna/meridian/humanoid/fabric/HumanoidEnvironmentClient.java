package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.RegionInventory;
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
final class HumanoidEnvironmentClient {
    private static final String ENTRYPOINT = "meridian_environment_host";
    private Method exchange;
    private Object endpoint;

    RegionSnapshot query(Entity entity) {
        // 以实体所在维度和区块坐标向 Human 查询当前区域快照。
        var position = position(entity);
        var request = EnvironmentHostMessages.EnvironmentHostRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(UUID.randomUUID().toString())
                .setQuery(position)
                .build();
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
        RegionInventory projected = RegionInventory.present(region.getConcentration(), entries);
        return new RegionSnapshot(projected, region.getSourceId());
    }

    EnvironmentHostMessages.EnvironmentHostResponse clockResponse() {
        // 时钟由 Human 唯一提供，响应必须包含非负且身份完整的游标。
        var request = EnvironmentHostMessages.EnvironmentHostRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(UUID.randomUUID().toString())
                .setClock(true /* queryClock */)
                .build();
        var response = exchange(request);
        if (response.getUnbound()) {
            throw unavailable("Human 世界时钟尚未绑定", MeridianMessages.FailureCode.ENVIRONMENT_UNAVAILABLE);
        }
        if (response.hasFailure()) {
            throw unavailable("Human 世界时钟查询失败", response.getFailure().getCode());
        }
        var now = response.getNow();
        if (!response.hasNow() || now.getDomain().isBlank()
                || now.getEpoch().isBlank() || now.getElapsedMillis() < 0) {
            throw unavailable("Human 世界时钟响应无效", MeridianMessages.FailureCode.INVALID_REQUEST);
        }
        return response;
    }

    boolean release(EnvironmentHostMessages.RegionRelease release) {
        // 成功和 duplicate 都通过 released 响应确认受理，其他结果交给调用方重试。
        var request = EnvironmentHostMessages.EnvironmentHostRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(UUID.randomUUID().toString())
                .setRelease(release)
                .build();
        var response = exchange(request);
        if (response.getUnbound() || response.hasFailure()) {
            return false;
        }
        return response.hasReleased();
    }

    private EnvironmentHostMessages.RegionQuery position(Entity entity) {
        // 环境服务接收方块坐标，区域查找规则由 Human 解释。
        return EnvironmentHostMessages.RegionQuery.newBuilder()
                .setDimensionId(entity.getWorld().getRegistryKey().getValue().toString())
                .setBlockX(entity.getBlockX())
                .setBlockZ(entity.getBlockZ())
                .build();
    }

    private EnvironmentHostMessages.EnvironmentHostResponse exchange(
            EnvironmentHostMessages.EnvironmentHostRequest request) {
        // 调用失败和协议解码失败分别保留环境不可用与内部错误的语义。
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
        // 已缓存的入口直接调用，缺少任一字段时在同步区内重新确认并绑定。
        Method cachedExchange = exchange;
        Object cachedEndpoint = endpoint;
        EndpointBinding binding;
        if (cachedExchange == null || cachedEndpoint == null) {
            binding = bindEndpoint();
        } else {
            binding = new EndpointBinding(cachedExchange, cachedEndpoint);
        }
        try {
            return (byte[]) binding.method().invoke(binding.target(), (Object) payload);
        } catch (IllegalAccessException | InvocationTargetException failure) {
            Throwable cause;
            if (failure instanceof InvocationTargetException invocation) {
                Throwable targetCause = invocation.getCause();
                cause = targetCause == null ? failure : targetCause;
            } else {
                cause = failure;
            }
            throw unavailable("Human 环境服务入口拒绝调用", MeridianMessages.FailureCode.ENVIRONMENT_UNAVAILABLE, cause);
        }
    }

    private synchronized EndpointBinding bindEndpoint() {
        // 同步后再次检查缓存，避免并发首次请求重复解析公开字节入口。
        Method cachedExchange = exchange;
        Object cachedEndpoint = endpoint;
        if (cachedExchange != null && cachedEndpoint != null) {
            return new EndpointBinding(cachedExchange, cachedEndpoint);
        }
        List<Object> entrypoints = FabricLoader.getInstance().getEntrypoints(ENTRYPOINT, Object.class);
        if (entrypoints.isEmpty()) {
            throw unavailable("没有 Human 环境服务入口", MeridianMessages.FailureCode.ENVIRONMENT_UNAVAILABLE);
        }
        Object installedEndpoint = entrypoints.get(0);
        Method installedExchange;
        try {
            installedExchange = installedEndpoint.getClass().getMethod("exchange", byte[].class);
        } catch (NoSuchMethodException failure) {
            throw unavailable("Human 环境服务入口协议不匹配", MeridianMessages.FailureCode.INTERNAL_ERROR, failure);
        }
        endpoint = installedEndpoint;
        exchange = installedExchange;
        return new EndpointBinding(installedExchange, installedEndpoint);
    }

    private static Unavailable unavailable(String message, MeridianMessages.FailureCode code) {
        // 没有底层异常时仍使用相同的公开失败码包装。
        return new Unavailable(message, code, null);
    }

    private static Unavailable unavailable(String message, MeridianMessages.FailureCode code, Throwable cause) {
        // 保留内部异常链供服务端日志定位，协议只暴露失败码。
        return new Unavailable(message, code, cause);
    }

    record RegionSnapshot(RegionInventory inventory, String sourceId) {
        RegionSnapshot {
            // 区域缺席用显式 absent 库存表示，快照字段本身不能为空。
            if (inventory == null || sourceId == null) {
                throw new IllegalArgumentException("区域快照不能为空");
            }
        }
    }

    private record EndpointBinding(Method method, Object target) {
    }

    static final class Unavailable extends RuntimeException {
        private final MeridianMessages.FailureCode code;

        Unavailable(String message, MeridianMessages.FailureCode code, Throwable cause) {
            super(message, cause);
            // 业务拒绝和协议错误通过固定失败码返回宿主。
            this.code = code;
        }

        MeridianMessages.FailureCode code() {
            // 调用方不需要依赖异常文案判断失败类型。
            return code;
        }
    }
}
