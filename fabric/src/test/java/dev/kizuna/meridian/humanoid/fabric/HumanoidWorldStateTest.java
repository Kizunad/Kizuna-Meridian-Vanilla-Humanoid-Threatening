package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.RegionQuery;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.RegionRelease;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import net.minecraft.nbt.NbtByteArray;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.*;

/** 持久交接列表是协议事实，损坏时不能吞成空列表。 */
public final class HumanoidWorldStateTest {
    private static final long NEXT_SEQUENCE = 7;
    private static final double RELEASE_AMOUNT = 2;

    @Rule
    public final TemporaryFolder directory = new TemporaryFolder();

    @Test
    public void legacyWriterFilesMigrateAndPersistWithoutChangingRequests() throws IOException {
        // 旧写入器生成真实文件，新读取器保留序号和完整请求，并在保存时写入新字段。
        net.minecraft.SharedConstants.createGameVersion();
        RegionRelease release = release();
        for (List<RegionRelease> pending : List.of(List.<RegionRelease>of(), List.of(release))) {
            NbtCompound legacy = writeLegacy(NEXT_SEQUENCE, pending);
            var file = directory.newFile().toPath();
            NbtCompound root = new NbtCompound();
            root.put("data", legacy);
            NbtIo.writeCompressed(root, file.toFile());

            NbtCompound saved = NbtIo.readCompressed(file.toFile()).getCompound("data");
            HumanoidWorldState restored = HumanoidWorldState.read(saved);
            assertFalse(saved.contains("pending_forget"));
            assertTrue(restored.isDirty());
            restored.save(file.toFile());

            NbtCompound upgraded = NbtIo.readCompressed(file.toFile()).getCompound("data");
            assertEquals(NEXT_SEQUENCE, upgraded.getLong("next_sequence"));
            assertEquals(legacy.get("pending"), upgraded.get("pending"));
            assertTrue(upgraded.contains("pending_forget", NbtElement.LIST_TYPE));
            assertTrue(upgraded.getList("pending_forget", NbtElement.STRING_TYPE).isEmpty());
            HumanoidWorldState reloaded = HumanoidWorldState.read(upgraded);
            assertFalse(reloaded.isDirty());
            assertEquals(upgraded, reloaded.writeNbt(new NbtCompound()));
        }
    }

    @Test
    public void migrationStillRejectsMalformedLegacyFieldsAndPresentForgetField() {
        // 迁移不能把损坏序号、请求列表或已存在的新字段当成合法空账本。
        RegionRelease release = release();
        NbtCompound valid = writeLegacy(NEXT_SEQUENCE, List.of(release));
        NbtCompound wrongSequence = valid.copy();
        wrongSequence.putLong("next_sequence", -1);
        NbtCompound missingSequence = valid.copy();
        missingSequence.remove("next_sequence");
        NbtCompound wrongPending = valid.copy();
        wrongPending.putString("pending", "invalid");
        NbtCompound wrongEntries = valid.copy();
        NbtList entries = new NbtList();
        entries.add(NbtString.of("invalid"));
        wrongEntries.put("pending", entries);
        NbtCompound duplicate = writeLegacy(NEXT_SEQUENCE, List.of(release, release));
        NbtCompound wrongForget = valid.copy();
        wrongForget.putString("pending_forget", "invalid");
        for (NbtCompound invalid : List.of(wrongSequence, missingSequence, wrongPending,
                wrongEntries, duplicate, wrongForget)) {
            assertThrows(IllegalArgumentException.class, () -> HumanoidWorldState.read(invalid));
        }
    }

    private static NbtCompound writeLegacy(long nextSequence, List<RegionRelease> pending) {
        // 只写序号和待释放字节列表，保留旧账本写入器的布局以覆盖真实升级入口。
        NbtCompound nbt = new NbtCompound();
        nbt.putLong("next_sequence", nextSequence);
        NbtList entries = new NbtList();
        for (RegionRelease release : pending) {
            entries.add(new NbtByteArray(release.toByteArray()));
        }
        nbt.put("pending", entries);
        return nbt;
    }

    private static RegionRelease release() {
        // 使用合法非空库存，验证迁移保留请求内容而非只接受空列表。
        RegionQuery position = RegionQuery.newBuilder()
                .setDimensionId("minecraft:overworld")
                .build();
        QiInventory qi = new QiInventory(List.of(new Qi(Qi.QiAttribute.of("qi_variants:wither"),
                RELEASE_AMOUNT, 1)));
        return RegionRelease.newBuilder()
                .setReleaseId("test:legacy-release")
                .setPosition(position)
                .setReleasedQi(BodyProtobuf.toInventory(qi))
                .build();
    }

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
