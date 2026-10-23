package dev.kizuna.meridian.humanoid.fabric.mixin;

import dev.kizuna.meridian.humanoid.fabric.HumanoidBodyState;
import dev.kizuna.meridian.humanoid.fabric.HumanoidEntityAccess;
import net.minecraft.entity.LivingEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/** 将身体快照按实体保存；查询宿主前不创建身体。 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityHumanoidMixin implements HumanoidEntityAccess {
    @Unique private HumanoidBodyState meridian$body;
    @Unique private byte[] meridian$savedBody;
    @Unique private byte[][] meridian$savedReceipts = new byte[0][];
    @Unique private String meridian$savedRegionSource = "";
    @Unique private UUID meridian$savedOwner;
    @Unique private boolean meridian$loading;
    @Unique private boolean meridian$releaseQueued;
    @Unique private boolean meridian$conversionPending;

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
        meridian$savedRegionSource = state == null ? "" : state.regionSource();
        meridian$savedOwner = ((net.minecraft.entity.Entity) (Object) this).getUuid();
    }

    @Override
    public byte[] meridian$humanoidSavedBody() {
        // 未绑定实体只返回读取 NBT 的原始快照，不能在此处触发创建。
        return meridian$savedBody;
    }

    @Override
    public void meridian$humanoidSavedBody(byte[] body, byte[][] receipts) {
        // NBT 读取阶段先缓存字节，第一次健康查询才恢复 Framework Store。
        meridian$humanoidSavedBody(body, receipts, "");
    }

    @Override
    public void meridian$humanoidSavedBody(byte[] body, byte[][] receipts, String regionSource) {
        // NBT 读取阶段先缓存字节，第一次健康查询才恢复 Framework Store。
        meridian$humanoidSavedBody(body, receipts, regionSource,
                ((net.minecraft.entity.Entity) (Object) this).getUuid());
    }

    @Override
    public void meridian$humanoidSavedBody(byte[] body, byte[][] receipts, String regionSource, UUID ownerId) {
        // 转化迁移可以保留旧身体 ID，但所有权改为当前实体，防止新实体恢复时误拒绝合法迁移。
        meridian$savedBody = body;
        meridian$savedReceipts = receipts == null ? new byte[0][] : receipts;
        meridian$savedRegionSource = regionSource == null ? "" : regionSource;
        meridian$savedOwner = ownerId == null ? ((net.minecraft.entity.Entity) (Object) this).getUuid() : ownerId;
    }

    @Override
    public byte[][] meridian$humanoidSavedReceipts() {
        // 返回不可修改的数组副本，宿主恢复时不会改变实体暂存数据。
        return java.util.Arrays.stream(meridian$savedReceipts).map(byte[]::clone).toArray(byte[][]::new);
    }

    @Override
    public String meridian$humanoidSavedRegionSource() {
        // 区域来源是出生元数据，只读返回，不能由调试命令改写。
        return meridian$savedRegionSource;
    }

    @Override
    public UUID meridian$humanoidSavedOwner() {
        // 未迁移的旧存档没有 owner_uuid 时由当前实体 UUID 补齐。
        return meridian$savedOwner == null ? ((net.minecraft.entity.Entity) (Object) this).getUuid()
                : meridian$savedOwner;
    }

    @Override
    public boolean meridian$humanoidLoading() {
        // 保存加载期间禁止其他附属通过健康查询抢先创建一具新身体。
        return meridian$loading;
    }

    @Override
    public void meridian$humanoidLoading(boolean loading) {
        // 读完 NBT 后由 mixin 尾部清除加载门闩。
        meridian$loading = loading;
    }

    @Override
    public boolean meridian$humanoidReleaseQueued() {
        return meridian$releaseQueued;
    }

    @Override
    public void meridian$humanoidReleaseQueued(boolean queued) {
        meridian$releaseQueued = queued;
    }

    @Override
    public boolean meridian$humanoidConversionPending() {
        return meridian$conversionPending;
    }

    @Override
    public void meridian$humanoidConversionPending(boolean pending) {
        meridian$conversionPending = pending;
    }

    @Override
    public void meridian$writeHumanoidNbt(NbtCompound nbt) {
        // 已创建身体写回最新快照；未创建身体保留原始字节，跨区块卸载不重抽。
        if (meridian$body == null && (meridian$savedBody == null || meridian$savedBody.length == 0)) {
            return;
        }
        byte[] body = meridian$body == null ? meridian$savedBody : meridian$body.encodeBody();
        byte[][] receipts = meridian$body == null ? meridian$savedReceipts : meridian$body.encodeReceipts();
        NbtCompound stored = new NbtCompound();
        stored.putByteArray("body", body);
        stored.putUuid("owner_uuid", meridian$body == null ? meridian$humanoidSavedOwner()
                : ((net.minecraft.entity.Entity) (Object) this).getUuid());
        stored.putString("region_source", meridian$body == null ? meridian$savedRegionSource
                : meridian$body.regionSource());
        NbtList receiptList = new NbtList();
        for (byte[] receipt : receipts) {
            receiptList.add(new net.minecraft.nbt.NbtByteArray(receipt));
        }
        stored.put("receipts", receiptList);
        nbt.put("meridian_humanoid", stored);
    }

    @Inject(method = "readCustomDataFromNbt", at = @At("HEAD"))
    private void meridian$beginRead(NbtCompound nbt, CallbackInfo callback) {
        // 读档期间保持未绑定，直到正文被完整解析并且实体再次被健康宿主查询。
        meridian$loading = true;
        if (!nbt.contains("meridian_humanoid", NbtElement.COMPOUND_TYPE)) {
            return;
        }
        NbtCompound stored = nbt.getCompound("meridian_humanoid");
        if (!stored.contains("body", NbtElement.BYTE_ARRAY_TYPE)
                || !stored.contains("receipts", NbtElement.LIST_TYPE)) {
            throw new IllegalArgumentException("类人实体身体 NBT 字段缺失");
        }
        meridian$savedBody = stored.getByteArray("body");
        if (meridian$savedBody.length == 0) {
            throw new IllegalArgumentException("类人实体身体快照不能为空");
        }
        meridian$savedRegionSource = stored.contains("region_source", NbtElement.STRING_TYPE)
                ? stored.getString("region_source") : "";
        if (stored.contains("owner_uuid") && !stored.contains("owner_uuid", NbtElement.INT_ARRAY_TYPE)) {
            throw new IllegalArgumentException("类人实体身体 owner_uuid 类型无效");
        }
        meridian$savedOwner = stored.contains("owner_uuid", NbtElement.INT_ARRAY_TYPE)
                ? stored.getUuid("owner_uuid") : ((net.minecraft.entity.Entity) (Object) this).getUuid();
        NbtList receiptList = stored.getList("receipts", NbtElement.BYTE_ARRAY_TYPE);
        if (receiptList.getHeldType() != NbtElement.END_TYPE
                && receiptList.getHeldType() != NbtElement.BYTE_ARRAY_TYPE
                || receiptList.size() > 32) {
            throw new IllegalArgumentException("类人实体身体回执列表无效");
        }
        meridian$savedReceipts = new byte[receiptList.size()][];
        for (int index = 0; index < receiptList.size(); index++) {
            meridian$savedReceipts[index] = ((net.minecraft.nbt.NbtByteArray) receiptList.get(index)).getByteArray();
        }
    }

    @Inject(method = "readCustomDataFromNbt", at = @At("TAIL"))
    private void meridian$finishRead(NbtCompound nbt, CallbackInfo callback) {
        // 未创建实体保持缓存状态；健康宿主会在主线程按实体类型验证后恢复。
        meridian$loading = false;
    }

    @Inject(method = "writeCustomDataToNbt", at = @At("TAIL"))
    private void meridian$writeBody(NbtCompound nbt, CallbackInfo callback) {
        // 快照与回执同属实体复合标签，确保区块卸载不会只保存一半身体。
        meridian$writeHumanoidNbt(nbt);
    }
}
