package dev.kizuna.meridian.humanoid.fabric;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;

import static org.junit.Assert.*;

/** 持久交接列表是协议事实，损坏时不能吞成空列表。 */
public final class HumanoidWorldStateTest {
    @Rule
    public final TemporaryFolder directory = new TemporaryFolder();

    @Test
    public void unsupportedDirectorySyncSavesActualWorldState() throws IOException {
        // 真实 NBT 保存也走不支持目录同步的路径，成功后清除 dirty 并可重载。
        net.minecraft.SharedConstants.createGameVersion();
        var state = new HumanoidWorldState(DurableStateFile.DirectorySync.UNSUPPORTED);
        var file = directory.getRoot().toPath().resolve("sender.dat");
        state.markDirty();
        state.save(file.toFile());
        assertFalse(state.isDirty());
        var saved = NbtIo.readCompressed(file.toFile()).getCompound("data");
        assertEquals(saved, HumanoidWorldState.read(saved).writeNbt(new NbtCompound()));
    }

    @Test
    public void forgetListRejectsWrongTypeBlankAndDuplicateIds() {
        // 校验原始标签元素类型与 ID 唯一性，禁止损坏文件抹掉待遗忘事实。
        var valid = new HumanoidWorldState().writeNbt(new NbtCompound());
        NbtList wrongType = new NbtList();
        wrongType.add(new NbtCompound());
        NbtList blank = new NbtList();
        blank.add(NbtString.of(""));
        NbtList duplicate = new NbtList();
        duplicate.add(NbtString.of("release:one"));
        duplicate.add(NbtString.of("release:one"));
        for (NbtList invalid : java.util.List.of(wrongType, blank, duplicate)) {
            var saved = valid.copy();
            saved.put("pending_forget", invalid);
            assertThrows(IllegalArgumentException.class, () -> HumanoidWorldState.read(saved));
            assertEquals(invalid, saved.getList("pending_forget", invalid.getHeldType()));
        }
        NbtList outstanding = new NbtList();
        outstanding.add(NbtString.of("release:pending-forget"));
        valid.put("pending_forget", outstanding);
        var restored = HumanoidWorldState.read(valid).writeNbt(new NbtCompound());
        assertEquals(outstanding, restored.getList("pending_forget", NbtElement.STRING_TYPE));
    }
}
