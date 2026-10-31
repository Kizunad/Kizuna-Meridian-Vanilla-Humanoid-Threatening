package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 类人实体释放账本；持久交接后只保留待遗忘 ID，直到 Human 持久回收去重项。 */
final class HumanoidWorldState extends SavedData {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(HumanoidWorldState.class);
    private final List<EnvironmentHostMessages.RegionRelease> pending = new ArrayList<>();
    private final Set<String> pendingForget = new LinkedHashSet<>();
    private final Set<String> durableForget = new HashSet<>();
    private final DurableStateFile.DirectorySync directorySync;
    private Path file;
    private boolean handoffDirty;
    private long nextSequence;

    HumanoidWorldState() {
        // 平台不支持目录通道时仍必须同步文件，不能因此阻止整个账本保存。
        this(DurableStateFile.directorySync(System.getProperty("os.name")));
    }

    HumanoidWorldState(DurableStateFile.DirectorySync directorySync) {
        // 保存者仅在完整提交后授权发送遗忘，目录能力不会改变交接顺序。
        this.directorySync = directorySync;
    }

    static HumanoidWorldState read(CompoundTag nbt) {
        // 世界文件只保存完整 RegionRelease 字节，加载时重新验证协议和库存明细。
        CompoundTag migrated = migrateLegacy(nbt);
        if (!migrated.contains("next_sequence", Tag.TAG_LONG)
                || !migrated.contains("pending", Tag.TAG_LIST)
                || !migrated.contains("pending_forget", Tag.TAG_LIST)) {
            throw new IllegalArgumentException("类人实体释放账本字段缺失");
        }
        HumanoidWorldState state = new HumanoidWorldState();
        state.nextSequence = migrated.getLong("next_sequence");
        if (state.nextSequence < 0) {
            throw new IllegalArgumentException("类人实体释放序号无效");
        }
        // getList 会把错误元素类型替换成空列表，必须检查原始标签才能拒绝损坏存档。
        ListTag entries = (ListTag) migrated.get("pending");
        byte entryType = entries.getElementType();
        if (entryType != Tag.TAG_END && entryType != Tag.TAG_BYTE_ARRAY) {
            throw new IllegalArgumentException("类人实体释放账本列表类型无效");
        }
        int entryCount = entries.size();
        for (int index = 0; index < entryCount; index++) {
            state.pending.add(readRelease(entries.get(index), state.pending));
        }
        state.readForget(migrated);
        if (migrated != nbt) {
            state.setDirty();
        }
        return state;
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
        int type = entries.getElementType();
        if (type != Tag.TAG_END && type != Tag.TAG_STRING) {
            throw new IllegalArgumentException("类人实体待遗忘列表类型无效");
        }
        for (int index = 0; index < entries.size(); index++) {
            String releaseId = entries.getString(index);
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
        if (nextSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("类人实体释放序号耗尽");
        }
        String releaseId = "meridian_humanoid:" + entityId + ":" + nextSequence++;
        var position = EnvironmentHostMessages.RegionQuery.newBuilder()
                .setDimensionId(entity.level().dimension().location().toString())
                .setBlockX(entity.getBlockX())
                .setBlockZ(entity.getBlockZ())
                .build();
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

    @Override
    public void save(File file) {
        // 删除队列的保存也须原子替换；失败保留旧文件，重启后以原 ID 安全重发。
        if (!isDirty()) {
            return;
        }
        try {
            var root = new CompoundTag();
            root.put("data", save(new CompoundTag()));
            NbtUtils.addCurrentDataVersion(root);
            DurableStateFile.write(file.toPath(), temporary -> NbtIo.writeCompressed(root, temporary.toFile()),
                    directorySync);
            durableForget.clear();
            durableForget.addAll(pendingForget);
            handoffDirty = false;
            setDirty(false /* dirty */);
        } catch (IOException failure) {
            LOG.error("类人实体释放账本写入失败，已保留待保存状态", failure);
        }
    }

    @Override
    public CompoundTag save(CompoundTag nbt) {
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
        return nbt;
    }
}
