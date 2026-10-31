package dev.kizuna.meridian.humanoid.fabric.mixin;

import dev.kizuna.meridian.humanoid.fabric.HumanoidBodyState;
import dev.kizuna.meridian.humanoid.fabric.HumanoidEntityAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** 将身体快照按实体保存；查询宿主前不创建身体。 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityHumanoidMixin implements HumanoidEntityAccess {
    @Unique private static final int meridian$MAX_SAVED_RECEIPTS = 32;
    @Unique private static final Logger meridian$LOGGER = LoggerFactory.getLogger("meridian_humanoid_host");
    @Unique private HumanoidBodyState meridian$body;
    @Unique private byte[] meridian$savedBody;
    @Unique private byte[][] meridian$savedReceipts = new byte[0][];
    @Unique private String meridian$savedRegionSource = "";
    @Unique private UUID meridian$savedOwner;
    @Unique private boolean meridian$loading;
    @Unique private CompoundTag meridian$preservedBodyNbt;
    @Unique private boolean meridian$loadFailed;
    @Unique private boolean meridian$loadFailureLogged;
    @Unique private boolean meridian$releaseQueued;

    @Override
    public HumanoidBodyState meridian$humanoidBody() {
        // 身体状态随实体对象生命周期存在，不使用全局 UUID 表强引用卸载实体。
        return meridian$body;
    }

    @Override
    public void meridian$humanoidBody(HumanoidBodyState state) {
        // 宿主完成快照校验后才发布实体级引用。
        meridian$body = state;
        meridian$savedBody = null;
        meridian$savedReceipts = new byte[0][];
        if (state == null) {
            meridian$savedRegionSource = "";
        } else {
            meridian$savedRegionSource = state.regionSource();
        }
        meridian$savedOwner = ((Entity) (Object) this).getUUID();
    }

    @Override
    public byte[] meridian$humanoidSavedBody() {
        // 未绑定实体只返回读取 NBT 的原始快照，不能在此处触发创建。
        return meridian$savedBody;
    }

    @Override
    public byte[][] meridian$humanoidSavedReceipts() {
        // 逐条克隆回执，宿主修改返回数组时不会改变实体暂存数据。
        byte[][] copy = new byte[meridian$savedReceipts.length][];
        for (int index = 0; index < meridian$savedReceipts.length; index++) {
            byte[] source = meridian$savedReceipts[index];
            int length = source.length;
            byte[] receiptCopy = new byte[length];
            System.arraycopy(source, 0, receiptCopy, 0, length);
            copy[index] = receiptCopy;
        }
        return copy;
    }

    @Override
    public String meridian$humanoidSavedRegionSource() {
        // 区域来源是出生元数据，只读返回，不能由调试命令改写。
        return meridian$savedRegionSource;
    }

    @Override
    public UUID meridian$humanoidSavedOwner() {
        // 旧存档没有 owner_uuid 时由当前实体 UUID 补齐。
        if (meridian$savedOwner == null) {
            return ((Entity) (Object) this).getUUID();
        }
        return meridian$savedOwner;
    }

    @Override
    public boolean meridian$humanoidLoading() {
        // 保存加载期间禁止其他附属通过健康查询抢先创建一具新身体。
        return meridian$loading;
    }

    @Override
    public boolean meridian$humanoidLoadFailed() {
        // 坏身体标签只允许原样保存，健康宿主必须继续拒绝使用这具身体。
        return meridian$loadFailed;
    }

    @Override
    public void meridian$beginHumanoidLoading() {
        // NBT 读取完成前阻止其他附属提前创建身体。
        meridian$loading = true;
        meridian$loadFailed = false;
        meridian$preservedBodyNbt = null;
    }

    @Override
    public void meridian$finishHumanoidLoading() {
        // 正常读完后释放原始副本；坏档继续保留，供后续实体和区块保存。
        meridian$loading = false;
        if (!meridian$loadFailed) {
            meridian$preservedBodyNbt = null;
        }
    }

    @Override
    public boolean meridian$humanoidReleaseQueued() {
        // 移除重入时使用已入队事实阻止重复释放。
        return meridian$releaseQueued;
    }

    @Override
    public void meridian$markHumanoidReleaseQueued() {
        // 标记释放请求已归世界账本处理。
        meridian$releaseQueued = true;
    }

    @Override
    public void meridian$writeHumanoidNbt(CompoundTag nbt) {
        // 已创建身体写回最新快照；未创建身体保留原始字节，跨区块卸载不重抽。
        if (meridian$preservedBodyNbt != null) {
            // 读取尚未完成或已经失败时保留原标签，不能让坏身体阻断整个实体保存。
            nbt.put("meridian_humanoid", meridian$preservedBodyNbt.copy());
            return;
        }
        if (meridian$body == null && (meridian$savedBody == null || meridian$savedBody.length == 0)) {
            return;
        }
        byte[] body;
        byte[][] receipts;
        UUID ownerId;
        String regionSource;
        if (meridian$body == null) {
            body = meridian$savedBody;
            receipts = meridian$savedReceipts;
            ownerId = meridian$humanoidSavedOwner();
            regionSource = meridian$savedRegionSource;
        } else {
            body = meridian$body.encodeBody();
            receipts = meridian$body.encodeReceipts();
            ownerId = ((Entity) (Object) this).getUUID();
            regionSource = meridian$body.regionSource();
        }
        CompoundTag stored = new CompoundTag();
        stored.putByteArray("body", body);
        stored.putUUID("owner_uuid", ownerId);
        stored.putString("region_source", regionSource);
        ListTag receiptList = new ListTag();
        for (byte[] receipt : receipts) {
            receiptList.add(new net.minecraft.nbt.ByteArrayTag(receipt));
        }
        stored.put("receipts", receiptList);
        nbt.put("meridian_humanoid", stored);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("HEAD"))
    private void meridian$beginRead(CompoundTag nbt, CallbackInfo callback) {
        // 先保留独立的原始标签，校验失败只隔离身体功能，不能中断整个实体读档。
        meridian$beginHumanoidLoading();
        if (!nbt.contains("meridian_humanoid", Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag stored = nbt.getCompound("meridian_humanoid");
        meridian$preservedBodyNbt = stored.copy();
        try {
            meridian$readBody(stored);
        } catch (RuntimeException failure) {
            meridian$loadFailed = true;
            meridian$body = null;
            if (!meridian$loadFailureLogged) {
                meridian$loadFailureLogged = true;
                Entity entity = (Entity) (Object) this;
                meridian$LOGGER.error("类人实体身体 NBT 加载失败，保留原始数据 subject={}",
                        entity.getStringUUID(), failure);
            }
        }
    }

    @Unique
    private void meridian$readBody(CompoundTag stored) {
        // 身体字段完整通过校验后才更新缓存，不能将坏回执当成合法空窗口。
        if (!stored.contains("body", Tag.TAG_BYTE_ARRAY)
                || !stored.contains("receipts", Tag.TAG_LIST)) {
            throw new IllegalArgumentException("类人实体身体 NBT 字段缺失");
        }
        byte[] savedBody = stored.getByteArray("body");
        if (savedBody.length == 0) {
            throw new IllegalArgumentException("类人实体身体快照不能为空");
        }
        byte[][] savedReceipts = meridian$readReceipts(stored);
        UUID savedOwner = meridian$readOwner(stored);

        // 所有必需字段通过校验后才更新实体缓存，坏档不能留下半恢复状态。
        meridian$savedBody = savedBody;
        meridian$savedReceipts = savedReceipts;
        meridian$savedOwner = savedOwner;
        if (stored.contains("region_source", Tag.TAG_STRING)) {
            meridian$savedRegionSource = stored.getString("region_source");
        } else {
            meridian$savedRegionSource = "";
        }
    }

    @Unique
    private byte[][] meridian$readReceipts(CompoundTag stored) {
        // 原始列表保留真实元素类型，不能用 getList 把错误类型吞成空回执窗口。
        ListTag receiptList = (ListTag) stored.get("receipts");
        byte receiptType = receiptList.getElementType();
        int receiptCount = receiptList.size();
        if (receiptType != Tag.TAG_END && receiptType != Tag.TAG_BYTE_ARRAY
                || receiptCount > meridian$MAX_SAVED_RECEIPTS) {
            throw new IllegalArgumentException("类人实体身体回执列表无效");
        }
        byte[][] receipts = new byte[receiptCount][];
        for (int index = 0; index < receiptCount; index++) {
            receipts[index] = ((net.minecraft.nbt.ByteArrayTag) receiptList.get(index)).getAsByteArray();
        }
        return receipts;
    }

    @Unique
    private UUID meridian$readOwner(CompoundTag stored) {
        // 旧存档缺少 owner_uuid 时补当前实体 UUID，错误类型仍拒绝恢复。
        boolean uuidPresent = stored.contains("owner_uuid", Tag.TAG_INT_ARRAY);
        if (stored.contains("owner_uuid") && !uuidPresent) {
            throw new IllegalArgumentException("类人实体身体 owner_uuid 类型无效");
        }
        if (uuidPresent) {
            return stored.getUUID("owner_uuid");
        }
        return ((Entity) (Object) this).getUUID();
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void meridian$finishRead(CompoundTag nbt, CallbackInfo callback) {
        // 未创建实体保持缓存状态；健康宿主会在主线程按实体类型验证后恢复。
        meridian$finishHumanoidLoading();
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void meridian$writeBody(CompoundTag nbt, CallbackInfo callback) {
        // 快照与回执同属实体复合标签，确保区块卸载不会只保存一半身体。
        meridian$writeHumanoidNbt(nbt);
    }
}
