package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.persistence.DurableStateFile;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.LumenExport;
import dev.kizuna.meridian.server.BodyExports;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * 类人实体释放账本；持久交接后只保留待遗忘 ID，直到 Human 持久回收去重项。
 *
 * <p>账本文件由宿主自己读写：原版存档数据改为异步原位写入，不能保证已确认的交接事实不被截断。
 */
final class HumanoidWorldState {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(HumanoidWorldState.class);
    private static final String VERSION_KEY = "MeridianStateVersion";
    private static final int FILE_VERSION = 1;
    private final List<EnvironmentHostMessages.RegionRelease> pending = new ArrayList<>();
    private final Set<String> pendingForget = new LinkedHashSet<>();
    private final Set<String> durableForget = new HashSet<>();
    private final DurableStateFile.DirectorySync directorySync;
    private Path file;
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("meridian-humanoid-save").factory());
    private long changes;
    private long savedChanges;
    private long donationChanges;
    private Write submitted;

    private record Write(long version, Set<String> forget, CompletableFuture<Void> done) { }
    private boolean handoffDirty;
    private long nextSequence;
    private final Map<DonationId, Departed> donations = new HashMap<>();

    private record DonationId(UUID owner, UUID operation) { }
    private record Departed(UUID owner, EnvironmentHostMessages.RegionQuery position, LumenExport receipt) { }

    HumanoidWorldState() {
        // 平台不支持目录通道时仍必须同步文件，不能因此阻止整个账本保存。
        this(DurableStateFile.directorySync(System.getProperty("os.name")));
    }

    HumanoidWorldState(DurableStateFile.DirectorySync directorySync) {
        // 保存者仅在完整提交后授权发送遗忘，目录能力不会改变交接顺序。
        this.directorySync = directorySync;
    }

    static HumanoidWorldState load(Path file) throws IOException {
        // 文件外层是带数据版本的压缩 NBT，正文在 data 字段；缺正文时按空正文校验并拒绝加载。
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        // 无版本键的旧档使用同一正文结构。有版本键时校验原始标签类型，防止数值转换掩盖坏档。
        Tag version = root.get(VERSION_KEY);
        if (version != null && (version.getId() != Tag.TAG_INT || root.getIntOr(VERSION_KEY, 0) != FILE_VERSION)) {
            throw new IOException(file + "：无法读取存档版本 " + version);
        }
        return read(root.getCompoundOrEmpty("data"));
    }

    static HumanoidWorldState read(CompoundTag nbt) {
        // 世界文件只保存完整 RegionRelease 字节，加载时重新验证协议和库存明细。
        CompoundTag migrated = migrateLegacy(nbt);
        if (!hasTag(migrated, "next_sequence", Tag.TAG_LONG)
                || !hasTag(migrated, "pending", Tag.TAG_LIST)
                || !hasTag(migrated, "pending_forget", Tag.TAG_LIST)) {
            throw new IllegalArgumentException("类人实体释放账本字段缺失");
        }
        HumanoidWorldState state = new HumanoidWorldState();
        state.nextSequence = migrated.getLong("next_sequence").orElseThrow();
        if (state.nextSequence < 0) {
            throw new IllegalArgumentException("类人实体释放序号无效");
        }
        // 按类型取值会把错误元素当作缺失，必须检查原始标签才能拒绝损坏存档；26.2 的列表可混放类型，逐个检查。
        ListTag entries = (ListTag) migrated.get("pending");
        requireElements(entries, Tag.TAG_BYTE_ARRAY, "类人实体释放账本列表类型无效");
        int entryCount = entries.size();
        for (int index = 0; index < entryCount; index++) {
            state.pending.add(readRelease(entries.get(index), state.pending));
        }
        state.readForget(migrated);
        state.readDonations(migrated);
        if (migrated != nbt) {
            state.setDirty();
        }
        return state;
    }

    private static boolean hasTag(CompoundTag nbt, String key, byte type) {
        // 26.2 按名取值会跨数值类型转换，先核对原始标签类型，坏档才不会被当作合法字段。
        Tag value = nbt.get(key);
        return value != null && value.getId() == type;
    }

    private static void requireElements(ListTag entries, byte type, String message) {
        // 任一元素类型不符即拒绝整份账本，与旧版按列表元素类型拒绝的结果一致。
        for (Tag entry : entries) {
            if (entry.getId() != type) {
                throw new IllegalArgumentException(message);
            }
        }
    }

    private static CompoundTag migrateLegacy(CompoundTag nbt) {
        // 旧账本没有待遗忘字段；仅补缺失字段，已有的错误类型仍交给完整校验拒绝。
        if (nbt.contains("pending_forget")) {
            return nbt;
        }
        CompoundTag migrated = nbt.copy();
        migrated.put("pending_forget", new ListTag());
        return migrated;
    }

    private void readForget(CompoundTag nbt) {
        // 遗忘列表属于已持久删除的证明，损坏或与待释放交叠时必须拒绝整份文件。
        ListTag entries = (ListTag) nbt.get("pending_forget");
        requireElements(entries, Tag.TAG_STRING, "类人实体待遗忘列表类型无效");
        for (int index = 0; index < entries.size(); index++) {
            String releaseId = entries.getString(index).orElseThrow();
            if (releaseId.isBlank() || !pendingForget.add(releaseId)
                    || pending.stream().anyMatch(release -> release.getReleaseId().equals(releaseId))) {
                throw new IllegalArgumentException("类人实体待遗忘记录无效");
            }
        }
        durableForget.addAll(pendingForget);
    }

    void bind(Path file) {
        // 只有权威世界文件的成功提交才能授权发送遗忘；独立内存队列不会自动写其他文件。
        this.file = file;
    }

    private static EnvironmentHostMessages.RegionRelease readRelease(
            Tag entry, List<EnvironmentHostMessages.RegionRelease> pending) {
        // 每条请求先校验库存和身份，再检查序列内是否重复，损坏记录使整份存档加载失败。
        try {
            var release = EnvironmentHostMessages.RegionRelease.parseFrom(((ByteArrayTag) entry).getAsByteArray());
            var releasedQi = release.getReleasedQi();
            // 这里只校验库存，非法属性、密度或元数会抛出协议校验异常。
            BodyProtobuf.fromInventory(releasedQi);
            String releaseId = release.getReleaseId();
            if (releaseId.isBlank() || !release.hasPosition()
                    || release.getPosition().getDimensionId().isBlank() || releasedQi.getEntriesCount() == 0) {
                throw new IllegalArgumentException("类人实体释放记录无效");
            }
            if (pending.stream().anyMatch(value -> value.getReleaseId().equals(releaseId))) {
                throw new IllegalArgumentException("类人实体释放记录重复");
            }
            return release;
        } catch (Exception failure) {
            throw new IllegalArgumentException("类人实体释放记录损坏", failure);
        }
    }

    void enqueue(java.util.UUID entityId, net.minecraft.world.entity.Entity entity, dev.kizuna.meridian.core.QiInventory qi) {
        // 释放 ID 在世界账本内递增；完整请求相同则只保留一条，重启后仍可安全重试。
        if (qi.entries().isEmpty()) {
            return;
        }
        enqueue(entityId, position(entity), qi);
    }

    private static EnvironmentHostMessages.RegionQuery position(net.minecraft.world.entity.Entity entity) {
        // 永久退场时固定位置，可能稍后才到账的退款仍回到该处。
        return EnvironmentHostMessages.RegionQuery.newBuilder()
                .setDimensionId(entity.level().dimension().identifier().toString())
                .setBlockX(entity.getBlockX())
                .setBlockZ(entity.getBlockZ())
                .build();
    }

    private void enqueue(UUID entityId, EnvironmentHostMessages.RegionQuery position, QiInventory qi) {
        // 普通释放与退场后的退款共用同一世界序号与可靠交接队列。
        if (nextSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("类人实体释放序号耗尽");
        }
        String releaseId = "meridian_humanoid:" + entityId + ":" + nextSequence++;
        var release = EnvironmentHostMessages.RegionRelease.newBuilder()
                .setReleaseId(releaseId)
                .setPosition(position)
                .setReleasedQi(BodyProtobuf.toInventory(qi))
                .build();
        pending.add(release);
        setDirty();
    }

    void enqueue(java.util.UUID entityId, net.minecraft.world.entity.Entity entity, BodyMessages.QiInventory qi) {
        // 健康死亡回执已经是协议库存，先解码后复用同一个 ID 和入账路径。
        enqueue(entityId, entity, BodyProtobuf.fromInventory(qi));
    }

    void retry() {
        // 每 tick 重发原请求；确认只把记录转成待遗忘，不能先请求对端删除去重保护。
        collectWrite();
        if (savedChanges < donationChanges) {
            // 退款来源和终态尚未一起落盘时不向环境发送，写盘失败后下一 tick 仍用原序号重试。
            if (submitted == null && file != null) {
                submitWrite(file);
            }
            return;
        }
        var iterator = pending.iterator();
        while (iterator.hasNext()) {
            EnvironmentHostMessages.RegionRelease release = iterator.next();
            if (HumanoidFabric.release(release)) {
                iterator.remove();
                pendingForget.add(release.getReleaseId());
                handoffDirty = true;
                setDirty();
            }
        }
        retryForget();
        if (handoffDirty && file != null) {
            save(file.toFile());
        }
    }

    private void retryForget() {
        // 只有上一次成功保存中已不存在原请求的 ID 可遗忘；响应丢失继续保留 ID。
        var iterator = pendingForget.iterator();
        while (iterator.hasNext()) {
            String releaseId = iterator.next();
            if (durableForget.contains(releaseId) && HumanoidFabric.forgetRelease(releaseId)) {
                iterator.remove();
                durableForget.remove(releaseId);
                handoffDirty = true;
                setDirty();
            }
        }
    }

    void setDirty() {
        // 变化只做标记，写盘集中在世界保存与 tick 末交接。
        changes++;
    }

    boolean isDirty() {
        // 写盘失败时标记保留，下次保存继续重试。
        collectWrite();
        return changes != savedChanges;
    }

    void save(File file) {
        // 删除队列的保存也须原子替换；失败保留旧文件，重启后以原 ID 安全重发。
        try {
            submitWrite(file.toPath()).join();
        } catch (CompletionException failure) {
            // collectWrite 统一记录失败，保留待重试的版本。
        }
        collectWrite();
    }

    private CompletableFuture<Void> submitWrite(Path path) {
        // 主线程截取独占的 NBT 与遗忘清单，单线程按版本顺序完成压缩、原子替换与 fsync。
        collectWrite();
        if (changes == savedChanges) {
            return CompletableFuture.completedFuture(null);
        }
        if (submitted != null && submitted.version() == changes) {
            return submitted.done();
        }
        var root = new CompoundTag();
        root.put("data", save(new CompoundTag()));
        NbtUtils.addCurrentDataVersion(root);
        root.putInt(VERSION_KEY, FILE_VERSION);
        var done = CompletableFuture.runAsync(() -> {
            try {
                DurableStateFile.write(path, output -> NbtIo.writeCompressed(root, output), directorySync);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, WRITER);
        submitted = new Write(changes, Set.copyOf(pendingForget), done);
        return done;
    }

    void saveInBackground(Path path) {
        // 普通世界保存与退场交接共用有序写入，停服和显式 flush 才等待最新提交。
        submitWrite(path);
    }

    private void collectWrite() {
        // 较新的提交包含较老的全部状态，只有成功且覆盖的版本能授权遗忘。
        if (submitted == null || !submitted.done().isDone()) {
            return;
        }
        var completed = submitted;
        submitted = null;
        try {
            completed.done().join();
            savedChanges = completed.version();
            durableForget.clear();
            durableForget.addAll(completed.forget());
            if (changes == savedChanges) {
                handoffDirty = false;
            }
        } catch (CompletionException failure) {
            LOG.error("类人实体释放账本写入失败，已保留待保存状态", failure.getCause());
        }
    }

    CompoundTag save(CompoundTag nbt) {
        // 实体移除与服务器停止都保存同一批完整请求，避免释放成功前丢失账。
        nbt.putLong("next_sequence", nextSequence);
        ListTag entries = new ListTag();
        for (var release : pending) {
            entries.add(new net.minecraft.nbt.ByteArrayTag(release.toByteArray()));
        }
        nbt.put("pending", entries);
        ListTag forgetIds = new ListTag();
        for (String releaseId : pendingForget) {
            forgetIds.add(StringTag.valueOf(releaseId));
        }
        nbt.put("pending_forget", forgetIds);
        if (donations.isEmpty()) {
            // 尚无出资来客永久退场的世界沿用原有账本形状。
            return nbt;
        }
        ListTag records = new ListTag();
        for (var departed : donations.values()) {
            var entry = new CompoundTag();
            entry.putString("owner", departed.owner().toString());
            entry.putByteArray("position", departed.position().toByteArray());
            entry.putByteArray("receipt", departed.receipt().toByteArray());
            records.add(entry);
        }
        nbt.put("donations", records);
        return nbt;
    }

    private void readDonations(CompoundTag nbt) {
        // 旧世界没有遗留出资；有记录时严格校验明细及案号唯一性。
        if (!nbt.contains("donations")) {
            return;
        }
        for (Tag raw : nbt.getList("donations").orElseThrow()) {
            var entry = raw.asCompound().orElseThrow();
            try {
                UUID owner = UUID.fromString(entry.getString("owner").orElseThrow());
                var location = entry.getByteArray("position").orElseThrow();
                var position = EnvironmentHostMessages.RegionQuery.parseFrom(location);
                var record = LumenExport.parseFrom(entry.getByteArray("receipt").orElseThrow());
                BodyExports.validate(record);
                var operation = UUID.fromString(record.getOperationId());
                var id = new DonationId(owner, operation);
                if (donations.putIfAbsent(id, new Departed(owner, position, record)) != null) {
                    throw new IllegalArgumentException("退场出资案号重复");
                }
            } catch (com.google.protobuf.InvalidProtocolBufferException malformed) {
                throw new IllegalArgumentException("退场出资存档损坏", malformed);
            }
        }
    }

    void archiveDonations(net.minecraft.world.entity.Entity entity, BodySnapshot body) {
        // 永久退场后只有退款才回归环境。在途资格随原案转入世界，保持领取人绑定。
        for (String key : body.addonProgress().keySet()) {
            if (key.startsWith(BodyExports.PREFIX)) {
                UUID operation = UUID.fromString(key.substring(BodyExports.PREFIX.length()));
                var receipt = BodyExports.find(body, operation).orElseThrow();
                var owner = entity.getUUID();
                var id = new DonationId(owner, operation);
                if (donations.putIfAbsent(id, new Departed(owner, position(entity), receipt)) == null) {
                    setDirty();
                    donationChanges = changes;
                }
            }
        }
    }

    Optional<LumenExport> departedDonation(UUID elder, UUID operation) {
        // 供方可观察在途阶段；跨宿主授权以 settleDeparted 的落盘回执为准。
        var departed = donations.get(new DonationId(elder, operation));
        if (departed == null) {
            return Optional.empty();
        }
        return Optional.of(departed.receipt());
    }

    CompletableFuture<LumenExport> settleDeparted(UUID elder, LumenExport allocation) {
        // 回款和终态同存一份世界账，响应丢失后按号查询不会再排第二份环境释放。
        BodyExports.validate(allocation);
        var operation = UUID.fromString(allocation.getOperationId());
        var previous = departedDonation(elder, operation).orElseThrow();
        if (!BodyExports.sameFunding(previous, allocation)) {
            throw new IllegalArgumentException("退场出资原约不符");
        }
        if (BodyExports.pending(allocation) || previous.equals(allocation)) {
            return submitWrite(file).thenApply(ignored -> previous);
        }
        if (!BodyExports.pending(previous)) {
            throw new IllegalArgumentException("退场出资已有其他结算");
        }
        var id = new DonationId(elder, operation);
        var position = donations.get(id).position();
        var refund = QiInventory.combine(allocation.getSharesList().stream()
                .map(share -> BodyProtobuf.fromInventory(share.getReturned())).toList());
        if (!refund.entries().isEmpty()) {
            enqueue(elder, position, refund);
        }
        donations.put(id, new Departed(elder, position, allocation));
        setDirty();
        donationChanges = changes;
        return submitWrite(file).thenApply(ignored -> allocation);
    }
}
