package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtByteArray;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.world.PersistentState;

import java.util.ArrayList;
import java.util.List;

/** 类人实体释放账本；实体卸载后仍保留原请求，成功或 duplicate 才移除。 */
final class HumanoidWorldState extends PersistentState {
    private final List<EnvironmentHostMessages.RegionRelease> pending = new ArrayList<>();
    private long nextSequence;

    static HumanoidWorldState read(NbtCompound nbt) {
        // 世界文件只保存完整 RegionRelease 字节，加载时重新验证协议和库存明细。
        if (!nbt.contains("next_sequence", NbtElement.LONG_TYPE)
                || !nbt.contains("pending", NbtElement.LIST_TYPE)) {
            throw new IllegalArgumentException("类人实体释放账本字段缺失");
        }
        HumanoidWorldState state = new HumanoidWorldState();
        state.nextSequence = nbt.getLong("next_sequence");
        if (state.nextSequence < 0) {
            throw new IllegalArgumentException("类人实体释放序号无效");
        }
        NbtList entries = nbt.getList("pending", NbtElement.BYTE_ARRAY_TYPE);
        byte entryType = entries.getHeldType();
        if (entryType != NbtElement.END_TYPE && entryType != NbtElement.BYTE_ARRAY_TYPE) {
            throw new IllegalArgumentException("类人实体释放账本列表类型无效");
        }
        int entryCount = entries.size();
        for (int index = 0; index < entryCount; index++) {
            state.pending.add(readRelease(entries.get(index), state.pending));
        }
        return state;
    }

    private static EnvironmentHostMessages.RegionRelease readRelease(
            NbtElement entry, List<EnvironmentHostMessages.RegionRelease> pending) {
        // 每条请求先校验库存和身份，再检查序列内是否重复，损坏记录使整份存档加载失败。
        try {
            var release = EnvironmentHostMessages.RegionRelease.parseFrom(((NbtByteArray) entry).getByteArray());
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

    void enqueue(java.util.UUID entityId, net.minecraft.entity.Entity entity, dev.kizuna.meridian.core.QiInventory qi) {
        // 释放 ID 在世界账本内递增；完整请求相同则只保留一条，重启后仍可安全重试。
        if (qi.entries().isEmpty()) {
            return;
        }
        if (nextSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("类人实体释放序号耗尽");
        }
        String releaseId = "meridian_humanoid:" + entityId + ":" + nextSequence++;
        var position = EnvironmentHostMessages.RegionQuery.newBuilder()
                .setDimensionId(entity.getWorld().getRegistryKey().getValue().toString())
                .setBlockX(entity.getBlockX())
                .setBlockZ(entity.getBlockZ())
                .build();
        var release = EnvironmentHostMessages.RegionRelease.newBuilder()
                .setReleaseId(releaseId)
                .setPosition(position)
                .setReleasedQi(BodyProtobuf.toInventory(qi))
                .build();
        pending.add(release);
        markDirty();
    }

    void enqueue(java.util.UUID entityId, net.minecraft.entity.Entity entity, BodyMessages.QiInventory qi) {
        // 健康死亡回执已经是协议库存，先解码后复用同一个 ID 和入账路径。
        enqueue(entityId, entity, BodyProtobuf.fromInventory(qi));
    }

    void retry() {
        // 每个 tick 最多尝试一次当前队列快照；失败记录保留，成功和 duplicate 才删除。
        var iterator = pending.iterator();
        while (iterator.hasNext()) {
            EnvironmentHostMessages.RegionRelease release = iterator.next();
            if (HumanoidFabric.release(release)) {
                iterator.remove();
                markDirty();
            }
        }
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        // 实体移除与服务器停止都保存同一批完整请求，避免释放成功前丢失账。
        nbt.putLong("next_sequence", nextSequence);
        NbtList entries = new NbtList();
        for (var release : pending) {
            entries.add(new net.minecraft.nbt.NbtByteArray(release.toByteArray()));
        }
        nbt.put("pending", entries);
        return nbt;
    }
}
