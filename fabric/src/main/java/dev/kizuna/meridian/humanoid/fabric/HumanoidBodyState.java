package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidBodyGenerator;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidVariant;
import dev.kizuna.meridian.human.server.HumanDamage;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import dev.kizuna.meridian.protocol.pb.HealthMessages;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 单个已加载类人实体的身体事务包装；权威状态仍由 Framework Store 持有。 */
public final class HumanoidBodyState {
    private static final int MAX_SAVED_RECEIPTS = 32;

    private final InMemoryBodyStore store;
    private final BodyCommandHandler<String> commands;
    private final String bodyId;
    private final String regionSource;
    private long targetMillis;

    private HumanoidBodyState(BodyMessages.BodySnapshot saved, List<MeridianMessages.CommandReceipt> receipts,
                               String regionSource, long initialTargetMillis) {
        // 恢复时把快照和回执一并交给 Framework，旧请求只能按同一身份做幂等重放。
        var core = BodyProtobuf.fromMessage(saved);
        store = new InMemoryBodyStore(List.of(core), receipts, dev.kizuna.meridian.core.QiInventory.empty());
        bodyId = core.id().value();
        this.regionSource = regionSource == null ? "" : regionSource;
        targetMillis = initialTargetMillis;
        var installedContent = HumanoidFabric.content();
        commands = new BodyCommandHandler<>(
                (caller, command) -> {
                    if (caller.equals(bodyId) && command.getBodyId().equals(bodyId)) {
                        return Optional.of(caller);
                    }
                    return Optional.empty();
                },
                (caller, command, body) -> true,
                (caller, body) -> timeAdvancePlan(body, this.targetMillis),
                store, (caller, receipt, before, after) -> { /* 实体写回 NBT 时保存已提交快照。 */ },
                installedContent.recipes(), (caller, body, plan) -> Optional.empty(),
                installedContent.actions(), installedContent.openings(), caller -> false,
                new HumanDamage());
    }

    private static MeridianMessages.TimeAdvancePlan timeAdvancePlan(
            dev.kizuna.meridian.core.BodySnapshot body, long targetMillis) {
        // 时间计划跟随查询或健康效果更新的目标，不能倒退已结算游标。
        var settledAt = body.settledAt();
        BodyMessages.TimeCursor target = BodyProtobuf.toTime(settledAt).toBuilder()
                .setElapsedMillis(Math.max(targetMillis, settledAt.elapsedMillis()))
                .build();
        return MeridianMessages.TimeAdvancePlan.newBuilder()
                .setTarget(target)
                .setMode(MeridianMessages.TimeAdvanceMode.ACTIVE)
                .setBiologicalSecondsPerSimulationSecond(1)
                .build();
    }

    static HumanoidBodyState born(HumanoidSpecies species, UUID entityId, long worldSeed,
                                   HumanoidEnvironmentClient.RegionSnapshot region,
                                   BodyMessages.TimeCursor now) {
        // 第一次健康查询才生成身体；区域库存和浓度由 Human 服务在同一主线程提供。
        var random = HumanoidBodyGenerator.randomFor(worldSeed, entityId);
        HumanoidBodyGenerator generator = HumanoidFabric.generator();
        var inventory = region.inventory();
        HumanoidVariant variant = generator.rollVariant(species, inventory, random);
        var request = new HumanoidBodyGenerator.GenerationRequest(species, variant, entityId, inventory, now);
        BodyMessages.BodySnapshot body = generator.generate(request, random);
        return new HumanoidBodyState(body, List.of(), region.sourceId(), now.getElapsedMillis());
    }

    static HumanoidBodyState restore(UUID entityId, SavedBody saved, BodyMessages.TimeCursor now) {
        // NBT 正文先按 Framework 快照和回执协议解码，任何损坏都停止绑定而不重生身体。
        UUID ownerId = saved.ownerId();
        if (entityId == null || ownerId == null || !entityId.equals(ownerId)) {
            throw new IllegalArgumentException("类人实体实体身份不能为空");
        }
        BodyMessages.BodySnapshot body = decodeBody(saved.bodyBytes());
        validateBodyIdentity(body);
        var receipts = new ArrayList<MeridianMessages.CommandReceipt>();
        for (byte[] bytes : saved.receiptBytes()) {
            receipts.add(MeridianProtocol.decodeReceipt(bytes));
        }
        var settledAt = body.getSettledAt();
        String domain = settledAt.getDomain();
        String epoch = settledAt.getEpoch();
        if (domain.isBlank() || epoch.isBlank()
                || !domain.equals(now.getDomain()) || !epoch.equals(now.getEpoch())) {
            throw new IllegalArgumentException("类人实体身体时钟域不匹配");
        }
        return new HumanoidBodyState(body, List.copyOf(receipts), saved.regionSource(), now.getElapsedMillis());
    }

    private static BodyMessages.BodySnapshot decodeBody(byte[] bodyBytes) {
        // 反序列化失败明确标记为快照损坏，不能改为新生身体。
        try {
            return BodyMessages.BodySnapshot.parseFrom(bodyBytes);
        } catch (com.google.protobuf.InvalidProtocolBufferException failure) {
            throw new IllegalArgumentException("类人实体身体快照损坏", failure);
        }
    }

    private static void validateBodyIdentity(BodyMessages.BodySnapshot body) {
        // 身体 ID 的前缀和 UUID 格式必须合法，所属实体另由 owner_uuid 校验。
        String bodyPrefix = "meridian_humanoid:entity/";
        String id = body.getId();
        if (!id.startsWith(bodyPrefix)) {
            throw new IllegalArgumentException("类人实体身体身份无效");
        }
        try {
            // 这里只校验 UUID 格式；非法值抛出 IllegalArgumentException。
            UUID.fromString(id.substring(bodyPrefix.length()));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("类人实体身体身份无效", invalid);
        }
    }

    BodyMessages.QiInventory settle(BodyMessages.TimeCursor now) {
        // 查询或受伤前只推进到当前世界时钟；没有事件时不在每 tick 重算身体。
        if (!sameClock(now)) {
            throw new IllegalArgumentException("类人实体身体时钟域不匹配");
        }
        targetMillis = Math.max(targetMillis, now.getElapsedMillis());
        BodyMessages.BodySnapshot body = snapshot();
        if (body.getSettledAt().getElapsedMillis() >= targetMillis || body.getVitality().getDead()) {
            return BodyMessages.QiInventory.getDefaultInstance();
        }
        var command = MeridianMessages.BodyCommand.newBuilder()
                .setBodyId(bodyId)
                .setExpectedRevision(body.getRevision())
                .setAdvanceTime(MeridianMessages.AdvanceTime.getDefaultInstance())
                .build();
        String requestId = UUID.randomUUID().toString();
        MeridianMessages.RequestEnvelope request = MeridianMessages.RequestEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(requestId)
                .setBodyCommand(command)
                .build();
        submit(request);
        return store.receipts().stream().filter(receipt -> receipt.getRequestId().equals(requestId)
                        && receipt.hasTimeAdvance())
                .map(MeridianMessages.CommandReceipt::getTimeAdvance)
                .map(MeridianMessages.TimeAdvanceResult::getReleasedQi)
                .findFirst().orElse(BodyMessages.QiInventory.getDefaultInstance());
    }

    BodyMessages.QiInventory apply(MeridianMessages.RequestEnvelope request, BodyMessages.TimeCursor now) {
        // 健康效果必须按调用方固定的 request_id 和 expected_revision 交给可信处理器。
        if (!sameClock(now)) {
            throw new IllegalArgumentException("类人实体身体时钟域不匹配");
        }
        targetMillis = Math.max(targetMillis, now.getElapsedMillis());
        byte[] responseBytes = commands.handleHealthEffect(bodyId, MeridianProtocol.encodeRequest(request));
        MeridianMessages.ResponseEnvelope response = MeridianProtocol.decodeResponse(responseBytes);
        if (response.hasFailure()) {
            throw new HumanoidHealthFailure(response.getFailure().getCode());
        }
        return healthReceipt(request.getRequestId()).map(HealthMessages.HealthResult::getReleasedQi)
                .orElse(BodyMessages.QiInventory.getDefaultInstance());
    }

    BodyMessages.BodySnapshot snapshot() {
        // Store 只返回已提交快照；不存在身体属于内部一致性错误。
        return BodyProtobuf.toMessage(store.find(new dev.kizuna.meridian.core.BodyId(bodyId)).orElseThrow());
    }

    Optional<dev.kizuna.meridian.protocol.pb.HealthMessages.HealthResult> healthReceipt(String requestId) {
        // 回执事实与当前身体同一 Store 读取，避免重新执行 HumanDamage。
        return store.receipts().stream().filter(receipt -> receipt.getRequestId().equals(requestId)
                && receipt.hasHealth()).map(MeridianMessages.CommandReceipt::getHealth).findFirst();
    }

    public String regionSource() {
        // 区域来源作为生成元数据保存，调试命令不会重新查询或改变身体。
        return regionSource;
    }

    public byte[] encodeBody() {
        // NBT 只封装 Framework 的协议字节，不复制或派生另一份身体数据。
        return BodyProtobuf.encodeSnapshot(store.find(new dev.kizuna.meridian.core.BodyId(bodyId)).orElseThrow());
    }

    public byte[][] encodeReceipts() {
        // Store 不保证遍历顺序；按提交版本保留最新 32 条，确保最近请求在重载后仍可重放。
        List<MeridianMessages.CommandReceipt> receipts = store.receipts().stream()
                .sorted(Comparator.comparingLong(receipt -> receipt.getCommitted().getRevision()))
                .toList();
        int receiptCount = receipts.size();
        int start = Math.max(0, receiptCount - MAX_SAVED_RECEIPTS);
        byte[][] result = new byte[receiptCount - start][];
        for (int index = start; index < receiptCount; index++) {
            result[index - start] = MeridianProtocol.encodeReceipt(receipts.get(index));
        }
        return result;
    }

    dev.kizuna.meridian.core.QiInventory releaseInventory() {
        // 自然消失的实体没有健康回执，直接合并经脉、器官和在途账户后交给世界释放队列。
        var body = store.find(new dev.kizuna.meridian.core.BodyId(bodyId)).orElseThrow();
        var inventories = new ArrayList<dev.kizuna.meridian.core.QiInventory>();
        body.meridianStates().forEach(state -> {
            inventories.add(state.lumenQi());
            inventories.add(state.wallQi());
        });
        body.organStates().forEach(organ -> {
            inventories.add(organ.buffer());
            var process = organ.process();
            if (process != null) {
                inventories.add(process.input());
            }
        });
        body.transits().forEach(transit -> inventories.add(transit.cargo()));
        return dev.kizuna.meridian.core.QiInventory.combine(inventories);
    }

    private void submit(MeridianMessages.RequestEnvelope request) {
        // 时间推进命令使用相同身份提交，失败只抛固定失败码。
        byte[] responseBytes = commands.handle(bodyId, MeridianProtocol.encodeRequest(request));
        MeridianMessages.ResponseEnvelope response = MeridianProtocol.decodeResponse(responseBytes);
        if (response.hasFailure()) {
            throw new HumanoidHealthFailure(response.getFailure().getCode());
        }
    }

    private boolean sameClock(BodyMessages.TimeCursor now) {
        // 同一次检查只读取一份已提交游标，域和 epoch 必须同时匹配。
        if (now == null) {
            return false;
        }
        var settledAt = snapshot().getSettledAt();
        return now.getDomain().equals(settledAt.getDomain())
                && now.getEpoch().equals(settledAt.getEpoch())
                && now.getElapsedMillis() >= 0;
    }

    record SavedBody(UUID ownerId, byte[] bodyBytes, byte[][] receiptBytes, String regionSource) {
    }

    static final class HumanoidHealthFailure extends RuntimeException {
        private final MeridianMessages.FailureCode code;

        HumanoidHealthFailure(MeridianMessages.FailureCode code) {
            // 宿主层把 Framework 业务拒绝映射回公开 health_host 失败码。
            this.code = code;
        }

        MeridianMessages.FailureCode code() {
            // 失败码保持原始协议枚举，不向调用方暴露内部异常文案。
            return code;
        }
    }
}
