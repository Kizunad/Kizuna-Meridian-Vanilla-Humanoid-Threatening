package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyTimeAdvancement;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.core.TimeCursor;
import dev.kizuna.meridian.humanoid.server.HumanoidBodyGenerator;
import dev.kizuna.meridian.humanoid.server.HumanoidDamagePolicy;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.ActivityMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.HealthMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.server.TechniqueErrors;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import dev.kizuna.meridian.technique.server.TechniqueState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** 单个已加载类人实体的身体事务包装；权威状态仍由 Framework Store 持有。 */
public final class HumanoidBodyState {
    private static final int MAX_SAVED_RECEIPTS = 32;
    private static final String DEBUG_CALLER_ID = "meridian_debug:command";
    /** 类人身体的老化倍率：身体时钟走一秒，年龄长一秒。 */
    private static final double AGING_RATE = 1;
    private static final long NO_LIFESPAN_END = Long.MAX_VALUE;

    private final InMemoryBodyStore store;
    private final BodyCommandHandler<String> commands;
    private final String bodyId;
    private final String regionSource;
    private long targetMillis;
    private String trustedActivityCaller;
    /**
     * 按已提交身体缓存的协议消息和功法进度。Store 每提交一次命令就换上一份新的不可变快照（版本加一），
     * 所以只要 Store 里还是 cachedBody / progressBody 这同一份快照，缓存就是当前版本；换了就重算。
     */
    private BodySnapshot cachedBody;
    private BodyMessages.BodySnapshot cachedMessage;
    private BodySnapshot progressBody;
    private TechniqueProgress cachedProgress;
    /** 同样按已提交身体缓存：这份身体的寿元在身体时钟的哪一毫秒用完；没有终点时是 NO_LIFESPAN_END。 */
    private BodySnapshot lifespanBody;
    private long lifespanEndMillis;

    private HumanoidBodyState(BodyMessages.BodySnapshot saved, List<MeridianMessages.CommandReceipt> receipts,
                               String regionSource, long initialTargetMillis, HumanoidSpecies species) {
        // 快照和回执由 Framework 恢复；可信功法调用保留自身身份以校验能力修正的来源。
        var core = BodyProtobuf.fromMessage(saved);
        store = new InMemoryBodyStore(List.of(core), receipts, QiInventory.empty());
        bodyId = core.id().value();
        this.regionSource = regionSource == null ? "" : regionSource;
        targetMillis = initialTargetMillis;
        var installedContent = HumanoidFabric.content();
        commands = new BodyCommandHandler<>(
                (caller, command) -> {
                    if (caller.equals(trustedActivityCaller)
                            && command.hasExecuteActivity() && command.getBodyId().equals(bodyId)) {
                        return Optional.of(caller);
                    }
                    if (caller.equals(TechniqueHost.CALLER_ID)
                            && command.hasExecuteActivity() && command.getBodyId().equals(bodyId)) {
                        return Optional.of(caller);
                    }
                    if (caller.equals(DEBUG_CALLER_ID) && command.getBodyId().equals(bodyId)) {
                        return Optional.of(caller);
                    }
                    if (caller.equals(bodyId) && command.getBodyId().equals(bodyId)) {
                        return Optional.of(caller);
                    }
                    return Optional.empty();
                },
                (caller, command, body) -> true,
                (caller, body) -> timeAdvancePlan(body, this.targetMillis),
                store, (caller, receipt, before, after) -> { /* 实体写回 NBT 时保存已提交快照。 */ },
                installedContent.recipes(), (caller, body, plan) -> Optional.empty(),
                installedContent.actions(), installedContent.openings(), DEBUG_CALLER_ID::equals,
                HumanoidDamagePolicy.forSpecies(species));
    }

    private static MeridianMessages.TimeAdvancePlan timeAdvancePlan(
            BodySnapshot body, long targetMillis) {
        // 时间计划跟随查询或健康效果更新的目标，不能倒退已结算游标。
        var settledAt = body.settledAt();
        BodyMessages.TimeCursor target = BodyProtobuf.toTime(settledAt).toBuilder()
                .setElapsedMillis(Math.max(targetMillis, settledAt.elapsedMillis()))
                .build();
        return MeridianMessages.TimeAdvancePlan.newBuilder()
                .setTarget(target)
                .setMode(MeridianMessages.TimeAdvanceMode.ACTIVE)
                .setBiologicalSecondsPerSimulationSecond(AGING_RATE)
                .build();
    }

    static HumanoidBodyState born(BirthSubject subject, long worldSeed,
                                   HumanoidEnvironmentClient.RegionSnapshot region,
                                   BodyMessages.TimeCursor now) {
        // 自然出生与指定档位共用随机流；强制档位仍消耗原有抽档随机数。
        // 这样可以保持其他抽样顺序。
        UUID entityId = subject.entityId();
        HumanoidSpecies species = subject.species();
        var random = HumanoidBodyGenerator.randomFor(worldSeed, entityId);
        HumanoidBodyGenerator generator = HumanoidFabric.generator();
        var inventory = region.inventory();
        HumanoidTier rolled = HumanoidTier.of(generator.rollVariant(species, inventory, random));
        HumanoidTier forced = subject.forcedTier();
        HumanoidTier tier = forced == null ? rolled : forced;
        var request = new HumanoidBodyGenerator.GenerationRequest(species, tier.variant(), entityId, inventory, now);
        BodyMessages.BodySnapshot body = generate(generator, subject.grade(), tier, request, random);
        return new HumanoidBodyState(body, List.of(), region.sourceId(), now.getElapsedMillis(), species);
    }

    private static BodyMessages.BodySnapshot generate(HumanoidBodyGenerator generator, BirthGrade grade,
                                                      HumanoidTier tier,
                                                      HumanoidBodyGenerator.GenerationRequest request,
                                                      RandomGenerator random) {
        // 凡人档不开脉，精锐两层全开、装填取上限，其余按变体抽取开脉。
        if (tier == HumanoidTier.MORTAL) {
            return generator.generateUnopened(request, random);
        }
        if (grade == BirthGrade.ELITE) {
            return generator.generateElite(request, random);
        }
        return generator.generate(request, random);
    }

    /** 出生等级：寻常，或招敌派来的精锐头目。 */
    enum BirthGrade { ORDINARY, ELITE }

    /**
     * 出生对象。
     *
     * @param forcedTier 为空时沿用自然抽档
     * @param grade 精锐只能配真炁档
     */
    record BirthSubject(HumanoidSpecies species, UUID entityId, HumanoidTier forcedTier, BirthGrade grade) {
        // 构造时核对，精锐必须指定真炁档。
        BirthSubject {
            // 等级不能为空；精锐的档位不能靠自然抽。
            Objects.requireNonNull(grade, "grade");
            if (grade == BirthGrade.ELITE && forcedTier != HumanoidTier.TRUE_QI) {
                throw new IllegalArgumentException("精锐出生必须指定真炁档");
            }
        }

        BirthSubject(HumanoidSpecies species, UUID entityId, HumanoidTier forcedTier) {
            // 寻常出生，绝大多数调用方用这个。
            this(species, entityId, forcedTier, BirthGrade.ORDINARY);
        }
    }

    static HumanoidBodyState restore(UUID entityId, SavedBody saved, BodyMessages.TimeCursor now,
                                     HumanoidSpecies species) {
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
        return new HumanoidBodyState(body, List.copyOf(receipts), saved.regionSource(), now.getElapsedMillis(), species);
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
        BodySnapshot body = currentBody();
        if (body.settledAt().elapsedMillis() >= targetMillis || body.vitality().dead()) {
            return BodyMessages.QiInventory.getDefaultInstance();
        }
        var command = MeridianMessages.BodyCommand.newBuilder()
                .setBodyId(bodyId)
                .setExpectedRevision(body.revision())
                .setAdvanceTime(MeridianMessages.AdvanceTime.getDefaultInstance())
                .build();
        String requestId = UUID.randomUUID().toString();
        MeridianMessages.RequestEnvelope request = MeridianMessages.RequestEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(requestId)
                .setBodyCommand(command)
                .build();
        submit(request);
        return receipt(requestId).filter(MeridianMessages.CommandReceipt::hasTimeAdvance)
                .map(MeridianMessages.CommandReceipt::getTimeAdvance)
                .map(MeridianMessages.TimeAdvanceResult::getReleasedQi)
                .orElse(BodyMessages.QiInventory.getDefaultInstance());
    }

    /**
     * 此刻结清以后身体时钟会停在哪一毫秒；只算不结清，和 settle(now) 之后 settled_at 的毫秒数是同一个数：
     * 世界时钟、已经请求过的结清目标、已结清游标三者里最晚的那个。
     * 下面几种情况为空，调用方改走会结清的完整路径：时钟域对不上；身体已死（时钟不再前进）；
     * 寿元在这个时刻或之前用完（真正结清会停在寿元终点并判死，时钟到不了这里）。
     */
    OptionalLong projectedClockMillis(BodyMessages.TimeCursor now) {
        // 不改 targetMillis：这里只是读数，结清目标仍由真正的查询或健康效果推进。
        BodySnapshot body = currentBody();
        if (!sameClock(now) || body.vitality().dead()) {
            return OptionalLong.empty();
        }
        long requested = Math.max(targetMillis, now.getElapsedMillis());
        long projected = Math.max(requested, body.settledAt().elapsedMillis());
        if (lifespanEndMillis(body) <= projected) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(projected);
    }

    private long lifespanEndMillis(BodySnapshot body) {
        // 寿元终点只由这份快照的年龄、寿命和已结清游标决定，同一份快照只算一次。
        if (body != lifespanBody) {
            lifespanEndMillis = BodyTimeAdvancement.nextLifespanBoundary(body, AGING_RATE)
                    .map(TimeCursor::elapsedMillis)
                    .orElse(NO_LIFESPAN_END);
            lifespanBody = body;
        }
        return lifespanEndMillis;
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

    boolean openOne(String meridianTag, double addedVolume) {
        // 带这个标签的经脉里已经有开了的就不动；一条都没开，就按构型里的先后开第一条，返回 true。
        // 走 Framework 可信活动里的经脉完成项，和正常开脉完成是同一条路：扩内腔体积、标记已开。
        BodyMessages.BodySnapshot body = snapshot();
        String first = null;
        for (int index = 0; index < body.getMeridianStatesCount(); index++) {
            BodyMessages.MeridianDefinition meridian = body.getStructure().getMeridians(index);
            if (!meridian.getTagsList().contains(meridianTag)) {
                continue;
            }
            var stage = body.getMeridianStates(index).getOpening().getStage();
            if (stage == BodyMessages.MeridianOpeningStage.OPENED) {
                return false;
            }
            first = first == null ? meridian.getId() : first;
        }
        if (first == null) {
            return false;
        }
        var completion = ActivityMessages.CompleteMeridian.newBuilder().setMeridianId(first).setAddedVolume(addedVolume);
        MeridianMessages.RequestEnvelope request = MeridianMessages.RequestEnvelope.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(UUID.randomUUID().toString())
                .setBodyCommand(MeridianMessages.BodyCommand.newBuilder()
                        .setBodyId(bodyId)
                        .setExpectedRevision(currentBody().revision())
                        .setExecuteActivity(ActivityMessages.BodyActivity.newBuilder().addCompletions(completion)))
                .build();
        byte[] responseBytes = commands.handleActivity(bodyId, MeridianProtocol.encodeRequest(request));
        MeridianMessages.ResponseEnvelope response = MeridianProtocol.decodeResponse(responseBytes);
        if (response.hasFailure()) {
            throw new HumanoidHealthFailure(response.getFailure().getCode());
        }
        return true;
    }

    void raiseArmGrowth(double growth) {
        // 两臂肌肉的训练成长抬到给定值：各自不超过训练定义的上限，只升不降；没有一块要抬时不发命令。
        // 走 Framework 的训练成长命令，身体的版本、回执和存盘照常。
        BodyMessages.BodySnapshot body = snapshot();
        var raise = MeridianMessages.RaiseTrainingGrowth.newBuilder();
        for (BodyMessages.TissueDefinition tissue : body.getStructure().getTissuesList()) {
            if (!tissue.getTagsList().contains(HumanoidFighters.ARM_MUSCLE) || !tissue.hasTraining()) {
                continue;
            }
            double target = Math.min(growth, tissue.getTraining().getMaximumGrowth());
            double current = body.getTissueStatesList().stream()
                    .filter(state -> state.getId().equals(tissue.getId()))
                    .mapToDouble(BodyMessages.TissueState::getTrainingGrowth)
                    .findFirst().orElse(0);
            if (target > current) {
                raise.addTissues(MeridianMessages.TissueGrowth.newBuilder().setTissueId(tissue.getId())
                        .setGrowth(target));
            }
        }
        if (raise.getTissuesCount() == 0) {
            return;
        }
        MeridianMessages.RequestEnvelope request = MeridianMessages.RequestEnvelope.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(UUID.randomUUID().toString())
                .setBodyCommand(MeridianMessages.BodyCommand.newBuilder()
                        .setBodyId(bodyId)
                        .setExpectedRevision(currentBody().revision())
                        .setRaiseTrainingGrowth(raise))
                .build();
        byte[] responseBytes = commands.handleTrainingGrowth(bodyId, MeridianProtocol.encodeRequest(request));
        MeridianMessages.ResponseEnvelope response = MeridianProtocol.decodeResponse(responseBytes);
        if (response.hasFailure()) {
            throw new HumanoidHealthFailure(response.getFailure().getCode());
        }
    }

    void exert(MeridianMessages.RequestEnvelope request, BodyMessages.TimeCursor now) {
        // 做功使用绑定身体的可信身份，保留请求版本及幂等 ID，不走健康专用命令入口。
        if (!sameClock(now)) {
            throw new IllegalArgumentException("类人实体身体时钟域不匹配");
        }
        if (!request.getBodyCommand().getBodyId().equals(bodyId)) {
            throw new HumanoidHealthFailure(MeridianMessages.FailureCode.ACCESS_DENIED);
        }
        targetMillis = Math.max(targetMillis, now.getElapsedMillis());
        submit(request);
    }

    MeridianMessages.ResponseEnvelope applyTechnique(TechniqueHostRequest request) {
        // 起手和结束保留服务生成的请求与预期版本，通过固定可信身份提交活动。
        var command = MeridianMessages.BodyCommand.newBuilder()
                .setBodyId(bodyId)
                .setExpectedRevision(request.getExpectedRevision())
                .setExecuteActivity(request.getActivity())
                .build();
        return recoveryResponse(request.getRequestId(), command);
    }

    MeridianMessages.ResponseEnvelope applyAddonActivity(String callerId, MeridianMessages.RequestEnvelope request) {
        // 身体已由健康路由推进时钟；保留请求版本，仅在本次调用范围授权附属来源。
        if (trustedActivityCaller != null) {
            throw new HumanoidHealthFailure(MeridianMessages.FailureCode.ACCESS_DENIED);
        }
        trustedActivityCaller = callerId;
        try {
            byte[] payload = MeridianProtocol.encodeRequest(request);
            return MeridianProtocol.decodeResponse(commands.handleActivity(callerId, payload));
        } finally {
            trustedActivityCaller = null;
        }
    }

    long submitRecovery(String requestId, MeridianMessages.BodyCommand command) {
        // 死体及未认领身体也可清理功法进度，仍检查命令版本及身份。
        var response = recoveryResponse(requestId, command);
        if (response.hasFailure()) {
            throw new TechniqueErrors.HostRejected(response.getFailure().getCode());
        }
        return response.getCommandCommitted().getRevision();
    }

    private MeridianMessages.ResponseEnvelope recoveryResponse(String requestId,
                                                               MeridianMessages.BodyCommand command) {
        // 两种可信命令都使用同一 Framework Store，实体 NBT 保存其回执和快照。
        var envelope = MeridianMessages.RequestEnvelope.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(requestId)
                .setBodyCommand(command)
                .build();
        byte[] payload = MeridianProtocol.encodeRequest(envelope);
        byte[] result;
        if (command.hasSetAddonProgress()) {
            result = commands.handleAddonProgress(bodyId, payload);
        } else {
            result = commands.handleActivity(TechniqueHost.CALLER_ID, payload);
        }
        return MeridianProtocol.decodeResponse(result);
    }

    BodyMessages.BodySnapshot snapshot() {
        // Store 只返回已提交快照；同一份快照的协议消息只转一次，提交换了快照才重转。
        BodySnapshot body = currentBody();
        if (body != cachedBody) {
            cachedMessage = BodyProtobuf.toMessage(body);
            cachedBody = body;
        }
        return cachedMessage;
    }

    TechniqueProgress techniqueProgress() {
        // 功法进度随身体保存；同一份快照只解一次，提交换了快照才重解。
        BodySnapshot body = currentBody();
        if (body != progressBody) {
            cachedProgress = TechniqueState.read(body);
            progressBody = body;
        }
        return cachedProgress;
    }

    BodySnapshot currentBody() {
        // 同宿主的只读属性投影直接使用已提交的不可变快照，避免逐 tick 编解码完整协议。
        return store.find(new BodyId(bodyId)).orElseThrow();
    }

    BodyMessages.BodySnapshot debugQi(DebugQi debugQi, BodyMessages.TimeCursor now) {
        // 调试入口只改写真元库存，仍通过同一 Framework 事务校验调试授权、版本和时间。
        if (!sameClock(now)) {
            throw new IllegalArgumentException("类人实体身体时钟域不匹配");
        }
        targetMillis = Math.max(targetMillis, now.getElapsedMillis());
        MeridianMessages.BodyCommand command = MeridianMessages.BodyCommand.newBuilder()
                .setBodyId(bodyId)
                .setExpectedRevision(currentBody().revision())
                .setDebugQi(debugQi)
                .build();
        String requestId = UUID.randomUUID().toString();
        MeridianMessages.RequestEnvelope request = MeridianMessages.RequestEnvelope.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(requestId)
                .setBodyCommand(command)
                .build();
        byte[] responseBytes = commands.handle(DEBUG_CALLER_ID,
                MeridianProtocol.encodeRequest(request));
        MeridianMessages.ResponseEnvelope response = MeridianProtocol.decodeResponse(responseBytes);
        if (response.hasFailure()) {
            throw new HumanoidHealthFailure(response.getFailure().getCode());
        }
        return snapshot();
    }

    Optional<HealthMessages.HealthResult> healthReceipt(String requestId) {
        // 回执事实与当前身体同一 Store 读取，避免重新执行 HumanDamage。
        return receipt(requestId).filter(MeridianMessages.CommandReceipt::hasHealth)
                .map(MeridianMessages.CommandReceipt::getHealth);
    }

    private Optional<MeridianMessages.CommandReceipt> receipt(String requestId) {
        // 时间推进和健康效果都以身体自己的身份提交；按「调用方 + 请求 ID」直接取，不遍历这具身体的全部回执。
        return store.transact(new BodyId(bodyId), transaction -> transaction.receipt(bodyId, requestId));
    }

    public String regionSource() {
        // 区域来源作为生成元数据保存，调试命令不会重新查询或改变身体。
        return regionSource;
    }

    public byte[] encodeBody() {
        // NBT 只封装 Framework 的协议字节，不复制或派生另一份身体数据。
        return BodyProtobuf.encodeSnapshot(store.find(new BodyId(bodyId)).orElseThrow());
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

    QiInventory releaseInventory() {
        // 自然消失的实体没有健康回执，直接合并经脉、器官和在途账户后交给世界释放队列。
        var body = store.find(new BodyId(bodyId)).orElseThrow();
        var inventories = new ArrayList<QiInventory>();
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
        return QiInventory.combine(inventories);
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
        var settledAt = BodyProtobuf.toTime(currentBody().settledAt());
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
            super(code.name());
            this.code = code;
        }

        MeridianMessages.FailureCode code() {
            // 失败码保持原始协议枚举，不向调用方暴露内部异常文案。
            return code;
        }
    }
}
