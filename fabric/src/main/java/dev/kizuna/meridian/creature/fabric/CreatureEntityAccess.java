package dev.kizuna.meridian.creature.fabric;

import net.minecraft.nbt.NbtCompound;

import java.util.UUID;

/** LivingEntity mixin 暴露的生物身体存档边界；不把 Minecraft 类型带入 server 纯 Java 层。 */
public interface CreatureEntityAccess {
    CreatureBodyState meridian$creatureBody();

    void meridian$creatureBody(CreatureBodyState state);

    byte[] meridian$creatureSavedBody();

    void meridian$creatureSavedBody(byte[] body, byte[][] receipts);

    void meridian$creatureSavedBody(byte[] body, byte[][] receipts, String regionSource);

    void meridian$creatureSavedBody(byte[] body, byte[][] receipts, String regionSource, UUID ownerId);

    byte[][] meridian$creatureSavedReceipts();

    String meridian$creatureSavedRegionSource();

    UUID meridian$creatureSavedOwner();

    boolean meridian$creatureLoading();

    void meridian$creatureLoading(boolean loading);

    boolean meridian$creatureReleaseQueued();

    void meridian$creatureReleaseQueued(boolean queued);

    boolean meridian$creatureConversionPending();

    void meridian$creatureConversionPending(boolean pending);

    void meridian$writeCreatureNbt(NbtCompound nbt);
}
