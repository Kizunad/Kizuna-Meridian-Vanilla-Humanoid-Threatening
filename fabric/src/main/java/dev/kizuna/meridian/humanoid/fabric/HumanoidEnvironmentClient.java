package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.RegionInventory;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;
import dev.kizuna.meridian.server.EnvironmentHostEntrypoint;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.Entity;
import java.util.List;
import java.util.UUID;

/** Human 区域服务的 Fabric 字节协议客户端；不直接依赖 Human Fabric 实现类。 */
final class HumanoidEnvironmentClient {
    private static final String ENTRYPOINT = "meridian_environment_host";
    private EnvironmentHostEntrypoint endpoint;

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
        // 只查询该请求是否已包含在成功保存中，duplicate 仍只表示此前受理。
        var request = EnvironmentHostMessages.EnvironmentHostRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(UUID.randomUUID().toString())
                .setRelease(release)
                .build();
        var response = exchange(request);
        if (response.getUnbound() || response.hasFailure()) {
            return false;
        }
        return response.hasReleased() && response.getReleased().getPersisted();
    }

    boolean forgetRelease(String releaseId) {
        // 此调用的前提是本地原请求已持久删除；对端未入账或删除尚未保存时继续重试。
        var request = EnvironmentHostMessages.EnvironmentHostRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(UUID.randomUUID().toString())
                .setForgetRelease(releaseId)
                .build();
        var response = exchange(request);
        return response.hasForgotten() && response.getForgotten().getPersisted();
    }

    private EnvironmentHostMessages.RegionQuery position(Entity entity) {
        // 环境服务接收方块坐标，区域查找规则由 Human 解释。
        return EnvironmentHostMessages.RegionQuery.newBuilder()
                .setDimensionId(entity.level().dimension().identifier().toString())
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
        // 已缓存的入口直接调用，未缓存时在同步区内重新确认并绑定；入口自身抛出的异常按拒绝调用上报。
        EnvironmentHostEntrypoint cachedEndpoint = endpoint;
        EnvironmentHostEntrypoint binding = cachedEndpoint == null ? bindEndpoint() : cachedEndpoint;
        try {
            return binding.exchange(payload);
        } catch (RuntimeException failure) {
            throw unavailable("Human 环境服务入口拒绝调用", MeridianMessages.FailureCode.ENVIRONMENT_UNAVAILABLE,
                    failure);
        }
    }

    private synchronized EnvironmentHostEntrypoint bindEndpoint() {
        // 同步后再次检查缓存，避免并发首次请求重复取入口；入口按 Framework 接口类型取出，不反射查方法。
        EnvironmentHostEntrypoint cachedEndpoint = endpoint;
        if (cachedEndpoint != null) {
            return cachedEndpoint;
        }
        List<EnvironmentHostEntrypoint> entrypoints = FabricLoader.getInstance()
                .getEntrypoints(ENTRYPOINT, EnvironmentHostEntrypoint.class);
        if (entrypoints.isEmpty()) {
            throw unavailable("没有 Human 环境服务入口", MeridianMessages.FailureCode.ENVIRONMENT_UNAVAILABLE);
        }
        EnvironmentHostEntrypoint installedEndpoint = entrypoints.get(0);
        endpoint = installedEndpoint;
        return installedEndpoint;
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
