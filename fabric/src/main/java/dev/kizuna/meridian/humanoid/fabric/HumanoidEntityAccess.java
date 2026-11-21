package dev.kizuna.meridian.humanoid.fabric;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

/** LivingEntity mixin 暴露的类人实体身体存档边界；不把 Minecraft 类型带入 server 纯 Java 层。 */
public interface HumanoidEntityAccess {
    long NO_LOAD_TIMESTAMP = -1;

    boolean meridian$awaitingVolumeBirth();

    void meridian$skipVolumeBirth();

    BirthVolumes meridian$birthVolumes();

    void meridian$birthVolumes(BirthVolumes volumes);

    HumanoidBirthPlan meridian$birthPlan();

    void meridian$birthPlan(HumanoidBirthPlan plan);

    HumanoidBodyState meridian$humanoidBody();

    void meridian$humanoidBody(HumanoidBodyState state);

    byte[] meridian$humanoidSavedBody();

    byte[][] meridian$humanoidSavedReceipts();

    String meridian$humanoidSavedRegionSource();

    UUID meridian$humanoidSavedOwner();

    boolean meridian$humanoidLoading();

    boolean meridian$humanoidLoadFailed();

    boolean meridian$dimensionTransferred();

    void meridian$markDimensionTransferred();

    long meridian$loadedAtMillis();

    void meridian$loadedAtMillis(long millis);

    void meridian$beginHumanoidLoading();

    void meridian$finishHumanoidLoading();

    boolean meridian$humanoidReleaseQueued();

    void meridian$markHumanoidReleaseQueued();

    void meridian$writeHumanoidNbt(CompoundTag nbt);
}
