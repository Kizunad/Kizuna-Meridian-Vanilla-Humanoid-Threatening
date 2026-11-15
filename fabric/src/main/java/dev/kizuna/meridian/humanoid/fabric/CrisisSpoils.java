package dev.kizuna.meridian.humanoid.fabric;

import com.google.protobuf.ByteString;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 一场里东西的来去：输了头目从玩家身上拿走一件，打死头目时掉回来，赢了掉的那卷。 */
final class CrisisSpoils {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_humanoid/crisis");

    private CrisisSpoils() {
        // 只有静态做法。
    }

    static ByteString take(ServerPlayer player) {
        // 先挑没读的卷轴，再挑带效果的药，从那一摞里拿一个；都没有就不拿。拿走的存成原版物品存档的 NBT。
        Inventory inventory = player.getInventory();
        Set<String> known = Set.copyOf(CrisisVolumes.known(player.getUUID()));
        OptionalInt slot = find(inventory, stack -> CrisisVolumes.volumeOf(stack)
                .filter(volume -> !known.contains(volume)).isPresent());
        if (slot.isEmpty()) {
            slot = find(inventory, CrisisSpoils::medicine);
        }
        if (slot.isEmpty()) {
            return ByteString.EMPTY;
        }
        ItemStack taken = inventory.getItem(slot.getAsInt()).split(1);
        inventory.setChanged();
        return encode(taken, player.registryAccess());
    }

    private static OptionalInt find(Inventory inventory, Predicate<ItemStack> wanted) {
        // 按格子顺序找第一摞合适的。
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && wanted.test(stack)) {
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }

    private static boolean medicine(ItemStack stack) {
        // 药是带效果的原版药水（喝的、喷溅、滞留）；白水瓶不算。
        boolean potion = stack.is(Items.POTION) || stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION);
        return potion && stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).hasEffects();
    }

    static ByteString encode(ItemStack stack, HolderLookup.Provider registries) {
        // 原版物品编解码器写成 NBT，再按原版 NBT 二进制存。
        var tag = ItemStack.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), stack)
                .getOrThrow();
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            NbtIo.write((CompoundTag) tag, out);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return ByteString.copyFrom(bytes.toByteArray());
    }

    static Optional<ItemStack> decode(ByteString saved, HolderLookup.Provider registries) {
        // 读不回来的（物品被删了、数据坏了）记一条错误，不掉。
        try (var in = new DataInputStream(new ByteArrayInputStream(saved.toByteArray()))) {
            CompoundTag tag = NbtIo.read(in);
            return ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag)
                    .resultOrPartial(error -> LOGGER.error("记仇的人身上的东西读不回来：{}", error));
        } catch (IOException corrupt) {
            LOGGER.error("记仇的人身上的东西读不回来", corrupt);
            return Optional.empty();
        }
    }

    static void drop(ServerLevel level, Entity at, List<ItemStack> stacks) {
        // 在它倒下的地方掉出来。
        stacks.forEach(stack -> at.spawnAtLocation(level, stack));
    }

    static List<ItemStack> carried(ServerLevel level, List<ByteString> saved) {
        // 记仇的人身上带着的，读得回来的都掉。
        return saved.stream().flatMap(bytes -> decode(bytes, level.registryAccess()).stream()).toList();
    }
}
