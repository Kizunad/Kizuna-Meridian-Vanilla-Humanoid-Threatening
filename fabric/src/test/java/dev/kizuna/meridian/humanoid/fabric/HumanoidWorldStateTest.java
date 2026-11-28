package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.persistence.DurableStateFile;

import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.RegionQuery;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.RegionRelease;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.LumenExport;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.LumenShare;
import dev.kizuna.meridian.server.BodyExports;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
        net.minecraft.SharedConstants.tryDetectVersion();
        RegionRelease release = release();
        for (List<RegionRelease> pending : List.of(List.<RegionRelease>of(), List.of(release))) {
            CompoundTag legacy = writeLegacy(NEXT_SEQUENCE, pending);
            var file = directory.newFile().toPath();
            CompoundTag root = new CompoundTag();
            root.put("data", legacy);
            NbtIo.writeCompressed(root, file);

            CompoundTag saved = readData(file);
            HumanoidWorldState restored = HumanoidWorldState.load(file);
            assertFalse(saved.contains("pending_forget"));
            assertTrue(restored.isDirty());
            restored.save(file.toFile());

            CompoundTag envelope = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            assertEquals(1, envelope.getIntOr("MeridianStateVersion", 0));
            CompoundTag upgraded = envelope.getCompoundOrEmpty("data");
            assertEquals(Optional.of(NEXT_SEQUENCE), upgraded.getLong("next_sequence"));
            assertEquals(legacy.get("pending"), upgraded.get("pending"));
            assertTrue(upgraded.get("pending_forget") instanceof ListTag forget && forget.isEmpty());
            HumanoidWorldState reloaded = HumanoidWorldState.load(file);
            assertFalse(reloaded.isDirty());
            assertEquals(upgraded, reloaded.save(new CompoundTag()));
        }
    }

    @Test
    public void migrationStillRejectsMalformedLegacyFieldsAndPresentForgetField() {
        // 迁移不能把损坏序号、请求列表或已存在的新字段当成合法空账本。
        RegionRelease release = release();
        CompoundTag valid = writeLegacy(NEXT_SEQUENCE, List.of(release));
        CompoundTag wrongSequence = valid.copy();
        wrongSequence.putLong("next_sequence", -1);
        CompoundTag missingSequence = valid.copy();
        missingSequence.remove("next_sequence");
        CompoundTag wrongPending = valid.copy();
        wrongPending.putString("pending", "invalid");
        CompoundTag wrongEntries = valid.copy();
        ListTag entries = new ListTag();
        entries.add(StringTag.valueOf("invalid"));
        wrongEntries.put("pending", entries);
        CompoundTag duplicate = writeLegacy(NEXT_SEQUENCE, List.of(release, release));
        CompoundTag wrongForget = valid.copy();
        wrongForget.putString("pending_forget", "invalid");
        for (CompoundTag invalid : List.of(wrongSequence, missingSequence, wrongPending,
                wrongEntries, duplicate, wrongForget)) {
            assertThrows(IllegalArgumentException.class, () -> HumanoidWorldState.read(invalid));
        }
    }

    private static CompoundTag readData(Path file) throws IOException {
        // 与宿主读取同一外层：压缩 NBT 的 data 字段是账本正文。
        return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("data");
    }

    private static CompoundTag writeLegacy(long nextSequence, List<RegionRelease> pending) {
        // 只写序号和待释放字节列表，保留旧账本写入器的布局以覆盖真实升级入口。
        CompoundTag nbt = new CompoundTag();
        nbt.putLong("next_sequence", nextSequence);
        ListTag entries = new ListTag();
        for (RegionRelease release : pending) {
            entries.add(new ByteArrayTag(release.toByteArray()));
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
        net.minecraft.SharedConstants.tryDetectVersion();
        var state = new HumanoidWorldState(DurableStateFile.DirectorySync.UNSUPPORTED);
        var file = directory.getRoot().toPath().resolve("sender.dat");
        state.setDirty();
        state.save(file.toFile());
        assertFalse(state.isDirty());
        var saved = readData(file);
        assertEquals(saved, HumanoidWorldState.read(saved).save(new CompoundTag()));
    }

    @Test
    public void failedDepartedRefundMustPersistBeforeRetryConfirmsIt() throws IOException {
        // 用普通文件作为父目录使退款无法落盘，再恢复目标目录，重试原单只能排出一笔退款。
        net.minecraft.SharedConstants.tryDetectVersion();
        var elder = UUID.randomUUID();
        var operation = UUID.randomUUID();
        var body = new HumanoidWorldState().save(new CompoundTag());
        var funding = archivedFunding(elder, operation);
        var entry = new CompoundTag();
        entry.putString("owner", elder.toString());
        entry.putByteArray("position", release().getPosition().toByteArray());
        entry.putByteArray("receipt", funding.toByteArray());
        var donations = new ListTag();
        donations.add(entry);
        body.put("donations", donations);
        var state = HumanoidWorldState.read(body);
        state.bind(directory.newFile().toPath().resolve("state.dat"));
        var refund = BodyExports.allocate(funding, 0);
        assertThrows(java.util.concurrent.CompletionException.class, () -> state.settleDeparted(elder, refund).join());
        assertThrows(java.util.concurrent.CompletionException.class, () -> state.settleDeparted(elder, refund).join());
        var file = directory.getRoot().toPath().resolve("recovered.dat");
        state.bind(file);
        assertEquals(refund, state.settleDeparted(elder, refund).join());
        var restored = HumanoidWorldState.load(file);
        assertEquals(Optional.of(refund), restored.departedDonation(elder, operation));
        var pending = restored.save(new CompoundTag()).getList("pending").orElseThrow();
        assertEquals(1, pending.size());
        var released = RegionRelease.parseFrom(((ByteArrayTag) pending.getFirst()).getAsByteArray());
        assertEquals(funding.getShares(0).getDebited(), released.getReleasedQi());
    }

    private static LumenExport archivedFunding(UUID elder, UUID operation) {
        // 已退场老人留下的单来源实扣，受方还没有领用。
        var cargo = release().getReleasedQi();
        var share = LumenShare.newBuilder()
                .setSource("source")
                .setDebited(cargo)
                .setPending(cargo);
        return LumenExport.newBuilder()
                .setOperationId(operation.toString())
                .setDonor(elder.toString())
                .setRecipient("student")
                .setTarget("target")
                .addShares(share)
                .build();
    }

    @Test
    public void forgetListRejectsWrongTypeBlankAndDuplicateIds() {
        // 校验原始标签元素类型与 ID 唯一性，禁止损坏文件抹掉待遗忘事实。
        var valid = new HumanoidWorldState().save(new CompoundTag());
        ListTag wrongType = new ListTag();
        wrongType.add(new CompoundTag());
        ListTag blank = new ListTag();
        blank.add(StringTag.valueOf(""));
        ListTag duplicate = new ListTag();
        duplicate.add(StringTag.valueOf("release:one"));
        duplicate.add(StringTag.valueOf("release:one"));
        for (ListTag invalid : java.util.List.of(wrongType, blank, duplicate)) {
            var saved = valid.copy();
            saved.put("pending_forget", invalid);
            assertThrows(IllegalArgumentException.class, () -> HumanoidWorldState.read(saved));
            assertEquals(invalid, saved.get("pending_forget"));
        }
        ListTag outstanding = new ListTag();
        outstanding.add(StringTag.valueOf("release:pending-forget"));
        valid.put("pending_forget", outstanding);
        var restored = HumanoidWorldState.read(valid).save(new CompoundTag());
        assertEquals(outstanding, restored.get("pending_forget"));
    }
}
