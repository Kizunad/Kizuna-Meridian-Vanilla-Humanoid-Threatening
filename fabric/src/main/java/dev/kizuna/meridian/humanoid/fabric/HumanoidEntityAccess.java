package dev.kizuna.meridian.humanoid.fabric;

import net.minecraft.nbt.NbtCompound;

import java.util.UUID;

/** LivingEntity mixin 暴露的类人实体身体存档边界；不把 Minecraft 类型带入 server 纯 Java 层。 */
public interface HumanoidEntityAccess {
    HumanoidBodyState meridian$humanoidBody();

    void meridian$humanoidBody(HumanoidBodyState state);

    byte[] meridian$humanoidSavedBody();

    void meridian$humanoidSavedBody(byte[] body, byte[][] receipts);

    void meridian$humanoidSavedBody(byte[] body, byte[][] receipts, String regionSource);

    void meridian$humanoidSavedBody(byte[] body, byte[][] receipts, String regionSource, UUID ownerId);

    byte[][] meridian$humanoidSavedReceipts();

    String meridian$humanoidSavedRegionSource();

    UUID meridian$humanoidSavedOwner();

    boolean meridian$humanoidLoading();

    void meridian$humanoidLoading(boolean loading);

    boolean meridian$humanoidReleaseQueued();

    void meridian$humanoidReleaseQueued(boolean queued);

    boolean meridian$humanoidConversionPending();

    void meridian$humanoidConversionPending(boolean pending);

    void meridian$writeHumanoidNbt(NbtCompound nbt);
}
