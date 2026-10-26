package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.EnvironmentHostRequest;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.EnvironmentHostResponse;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.RegionQuery;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.RegionRelease;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.ReleaseResult;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.NbtByteArray;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** 整体回归：经公开字节入口交接，分别注入响应丢失和两端文件写入失败。 */
public final class ReleaseHandoffGameTest implements FabricGameTest {
    private static final double RELEASE_AMOUNT = 4;
    private static final double RELEASE_DENSITY = 0.01;
    private static final int LOST_RESPONSE_COUNT = 2;
    private static final int HANDOFF_TICKS = 4;
    private static final int CREDIT_WINDOW_TICKS = 600;
    private static final int FORGET_TEST_LIMIT = 750;

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "handoff-directory-unsupported")
    public void unsupportedDirectorySyncPersistsSenderDeletionBeforeForget(TestContext context) throws Exception {
        // 同一 Windows 能力路径也必须保存发送端删除与待遗忘事实。
        RegionRelease release = release(context);
        HumanoidWorldState sender = new HumanoidWorldState(DurableStateFile.DirectorySync.UNSUPPORTED);
        Field pending = HumanoidWorldState.class.getDeclaredField("pending");
        pending.setAccessible(true /* allowAccess */);
        @SuppressWarnings("unchecked")
        List<RegionRelease> queue = (List<RegionRelease>) pending.get(sender);
        queue.add(release);
        Path file = Files.createTempDirectory(Path.of("."), "sender-windows-").resolve("sender.dat");
        sender.bind(file);
        sender.retry();
        later(context, () -> {
            sender.retry();
            context.assertTrue(!sender.isDirty(), "目录不支持导致发送端保存失败");
            var saved = NbtIo.readCompressed(file.toFile()).getCompound("data");
            context.assertTrue(saved.getList("pending", NbtElement.BYTE_ARRAY_TYPE).isEmpty()
                    && saved.getList("pending_forget", NbtElement.STRING_TYPE).size() == 1,
                    "发送端没有持久保存删除与待遗忘 ID");
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "handoff-memory")
    public void acceptanceAndDuplicateWithoutPersistenceKeepSenderRecord(TestContext context) throws Exception {
        // 明确的内存受理响应不能授权删除，duplicate 也不能替代 persisted。
        RegionRelease release = release(context);
        HumanoidWorldState sender = queued(release);
        Field binding = binding();
        Object delegate = binding.get(null);
        var duplicate = ReleaseResult.newBuilder()
                .setDuplicate(true /* duplicate */)
                .build();
        try {
            for (ReleaseResult result : List.of(ReleaseResult.getDefaultInstance(), duplicate)) {
                Function<byte[], byte[]> reply = payload -> acceptedResponse(payload, result);
                binding.set(null, reply);
                sender.retry();
                context.assertTrue(AuditReproSupport.pending(sender).equals(List.of(release)),
                        "没有持久化确认却删除了原请求");
            }
        } finally {
            binding.set(null, delegate);
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "handoff-lost-response")
    public void lostInitialAndDuplicateResponsesAndUnsavedDeletionRetrySafely(TestContext context) throws Exception {
        // 首次受理响应丢失，tick 末保存后的重复响应也丢失，发送端持续保留原请求。
        RegionRelease release = release(context);
        HumanoidWorldState sender = queued(release);
        NbtCompound oldSenderFile = sender.writeNbt(new NbtCompound());
        Field binding = binding();
        @SuppressWarnings("unchecked")
        Function<byte[], byte[]> delegate = (Function<byte[], byte[]>) binding.get(null);
        try {
            Function<byte[], byte[]> loseResponse = payload -> discardResponse(delegate, payload);
            binding.set(null, loseResponse);
            sender.retry();
            context.assertTrue(AuditReproSupport.pending(sender).equals(List.of(release)), "首次响应丢失后丢记录");
        } finally {
            binding.set(null, delegate);
        }
        later(context, () -> verifyLostDuplicate(context, sender, release, oldSenderFile));
    }

    private static void verifyLostDuplicate(TestContext context, HumanoidWorldState sender,
                                            RegionRelease release, NbtCompound oldSenderFile) throws Exception {
        // tick 末写盘后丢失重复响应，旧发送文件重放仍只能取回同一笔确认。
        Field binding = binding();
        @SuppressWarnings("unchecked")
        Function<byte[], byte[]> delegate = (Function<byte[], byte[]>) binding.get(null);
        try {
            binding.set(null, (Function<byte[], byte[]>) payload -> discardResponse(delegate, payload));
            for (int attempt = 0; attempt < LOST_RESPONSE_COUNT; attempt++) {
                sender.retry();
                context.assertTrue(AuditReproSupport.pending(sender).equals(List.of(release)), "重复响应丢失后丢记录");
                assertOneDurableCredit(context, release);
            }
        } finally {
            binding.set(null, delegate);
        }
        sender.retry();
        context.assertTrue(AuditReproSupport.pending(sender).isEmpty(), "持久确认后未删除队列");
        HumanoidWorldState restored = HumanoidWorldState.read(oldSenderFile);
        restored.retry();
        context.assertTrue(AuditReproSupport.pending(restored).isEmpty(), "发送端旧队列无法安全重放");
        assertOneDurableCredit(context, release);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "handoff-receiver-failure")
    public void receiverWriteFailureDoesNotConfirmAndKeepsSenderRecord(TestContext context) throws Exception {
        // 临时路径被目录占用时，真实 Human 服务不能确认持久化，旧正式文件保持完整。
        RegionRelease release = release(context);
        HumanoidWorldState sender = queued(release);
        Path humanFile = AuditReproSupport.worldFile(context, AuditReproSupport.HUMAN_FILE);
        AuditReproSupport.save(AuditReproSupport.human(), humanFile);
        byte[] original = Files.readAllBytes(humanFile);
        Path blocked = humanFile.resolveSibling(humanFile.getFileName() + ".tmp");
        Files.createDirectory(blocked);
        EnvironmentHostResponse accepted = exchange(release);
        context.assertTrue(accepted.hasReleased() && !accepted.getReleased().getPersisted(), "受理不应同步写盘");
        later(context, () -> {
            try {
                sender.retry();
                context.assertTrue(AuditReproSupport.pending(sender).equals(List.of(release)), "写盘失败后删除队列");
                context.assertTrue(Arrays.equals(original, Files.readAllBytes(humanFile)), "失败写入损坏 Human 文件");
                context.assertTrue(AuditReproSupport.human().isDirty(), "接收端写入失败清除了 dirty");
            } finally {
                Files.delete(blocked);
            }
            later(context, () -> {
                sender.retry();
                context.assertTrue(AuditReproSupport.pending(sender).isEmpty(), "接收端恢复后未完成交接");
                assertOneDurableCredit(context, release);
                context.complete();
            });
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "handoff-sender-failure")
    public void senderDeletionWriteFailureKeepsReplayableOldFile(TestContext context) throws Exception {
        // 删除保存失败后保留原始文件；重新加载旧请求也只能得到原释放的持久化确认。
        RegionRelease release = release(context);
        HumanoidWorldState sender = queued(release);
        Path directory = Files.createTempDirectory(Path.of("."), "sender-persistence-");
        Path file = directory.resolve("sender.dat");
        AuditReproSupport.save(sender, file);
        byte[] oldFile = Files.readAllBytes(file);
        sender.retry();
        later(context, () -> verifySenderFailure(context, sender, release, file, oldFile));
    }

    private static void verifySenderFailure(TestContext context, HumanoidWorldState sender,
                                            RegionRelease release, Path file, byte[] oldFile) throws Exception {
        // 收到确认后阻止发送端保存，重复 retry 仍不能授权对端遗忘。
        sender.retry();
        context.assertTrue(AuditReproSupport.pending(sender).isEmpty(), "接收端未确认真实释放");
        Path blocked = file.resolveSibling(file.getFileName() + ".tmp");
        Files.createDirectory(blocked);
        try {
            sender.save(file.toFile());
            context.assertTrue(sender.isDirty(), "发送端保存失败清除了 dirty");
            context.assertTrue(Arrays.equals(oldFile, Files.readAllBytes(file)), "发送端旧队列文件被截断");
            assertNoForgetBeforeSave(context, sender);
        } finally {
            Files.delete(blocked);
        }
        HumanoidWorldState restored = HumanoidWorldState.read(NbtIo.readCompressed(file.toFile()).getCompound("data"));
        context.assertTrue(AuditReproSupport.pending(restored).equals(List.of(release)), "没有从文件恢复原请求");
        restored.retry();
        context.assertTrue(AuditReproSupport.pending(restored).isEmpty(), "旧请求重放未成功");
        assertOneDurableCredit(context, release);
        sender.save(file.toFile());
        context.assertTrue(!sender.isDirty(), "发送端磁盘恢复后未保存删除");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "handoff-forget",
            tickLimit = FORGET_TEST_LIMIT)
    public void durableForgetRetriesLostResponsesAndReclaimsBothFiles(TestContext context) throws Exception {
        // 本地删除及待遗忘先提交，Human 入账后才回收，遗忘响应丢失仍保留重试事实。
        var position = RegionQuery.newBuilder().setDimensionId("minecraft:the_nether").build();
        RegionRelease release = release(context).toBuilder().setPosition(position).build();
        HumanoidWorldState sender = queued(release);
        Path file = Files.createTempDirectory(Path.of("."), "sender-forget-").resolve("sender.dat");
        sender.bind(file);
        AuditReproSupport.save(sender, file);
        sender.retry();
        later(context, () -> {
            sender.retry();
            context.assertTrue(AuditReproSupport.pending(sender).isEmpty(), "确认后未转为待遗忘");
            NbtCompound saved = NbtIo.readCompressed(file.toFile()).getCompound("data");
            context.assertTrue(saved.getList("pending", NbtElement.BYTE_ARRAY_TYPE).isEmpty()
                    && saved.getList("pending_forget", NbtElement.STRING_TYPE).size() == 1,
                    "没有先持久删除原请求并保存待遗忘 ID");
            context.assertTrue(AuditReproSupport.humanHasRelease(AuditReproSupport.human(), release.getReleaseId()),
                    "尚未发送遗忘却删除去重事实");
            context.waitAndRun(CREDIT_WINDOW_TICKS, () -> {
                try {
                    verifyForgetResponses(context, sender, release, file);
                } catch (Exception failure) {
                    throw new IllegalStateException("遗忘回归失败", failure);
                }
            });
        });
    }

    private static void verifyForgetResponses(TestContext context, HumanoidWorldState sender,
                                              RegionRelease release, Path file) throws Exception {
        // 先触发删除，再等 tick 末保存；首次删除响应尚未确认，发送端继续持有待遗忘。
        Field binding = binding();
        Object original = binding.get(null);
        try {
            binding.set(null, (Function<byte[], byte[]>) payload -> {
                throw new IllegalStateException("注入遗忘请求未送达");
            });
            sender.retry();
            context.assertTrue(AuditReproSupport.humanHasRelease(AuditReproSupport.human(), release.getReleaseId()),
                    "未送达的遗忘请求改变了接收端");
        } finally {
            binding.set(null, original);
        }
        sender.retry();
        later(context, () -> verifyLostForgetResponse(context, sender, release, file));
    }

    private static void verifyLostForgetResponse(TestContext context, HumanoidWorldState sender,
                                                 RegionRelease release, Path file) throws Exception {
        // 删除已落盘后的响应丢失，只重试遗忘；重启不能重新构造原释放。
        Field binding = binding();
        @SuppressWarnings("unchecked")
        Function<byte[], byte[]> delegate = (Function<byte[], byte[]>) binding.get(null);
        try {
            binding.set(null, (Function<byte[], byte[]>) payload -> discardResponse(delegate, payload));
            sender.retry();
            context.assertTrue(!AuditReproSupport.humanFileHasRelease(context, release.getReleaseId()),
                    "接收端去重项未持久删除");
            context.assertTrue(sender.writeNbt(new NbtCompound())
                    .getList("pending_forget", NbtElement.STRING_TYPE).size() == 1, "丢响应后丢待遗忘 ID");
        } finally {
            binding.set(null, delegate);
        }
        HumanoidWorldState restored = HumanoidWorldState.read(
                NbtIo.readCompressed(file.toFile()).getCompound("data"));
        restored.bind(file);
        restored.retry();
        context.assertTrue(NbtIo.readCompressed(file.toFile()).getCompound("data")
                .getList("pending_forget", NbtElement.STRING_TYPE).isEmpty(), "重启重试未回收发送文件");
        context.assertTrue(HumanoidFabric.forgetRelease(release.getReleaseId()), "重复遗忘不幂等");
        context.complete();
    }

    private static void assertNoForgetBeforeSave(TestContext context, HumanoidWorldState sender) throws Exception {
        // 直接统计公开请求，不能仅依赖接收端尚未入账而拒绝删除来掩盖发送顺序错误。
        Field binding = binding();
        @SuppressWarnings("unchecked")
        Function<byte[], byte[]> delegate = (Function<byte[], byte[]>) binding.get(null);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try {
            binding.set(null, (Function<byte[], byte[]>) payload -> {
                try {
                    if (EnvironmentHostRequest.parseFrom(payload).hasForgetRelease()) {
                        calls.incrementAndGet();
                    }
                } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
                    throw new IllegalArgumentException("测试请求无法解码", invalid);
                }
                return delegate.apply(payload);
            });
            sender.retry();
            context.assertTrue(calls.get() == 0, "本地删除未持久化就发送遗忘");
        } finally {
            binding.set(null, delegate);
        }
    }

    private static RegionRelease release(TestContext context) {
        // 使用真实维度及独立 ID，不借助实体生命周期生成额外的测试释放。
        var position = RegionQuery.newBuilder()
                .setDimensionId(context.getWorld().getRegistryKey().getValue().toString())
                .build();
        var qi = new QiInventory(List.of(new Qi(Qi.QiAttribute.of("qi_variants:wither"),
                RELEASE_AMOUNT, RELEASE_DENSITY)));
        return RegionRelease.newBuilder()
                .setReleaseId("gametest:handoff:" + UUID.randomUUID())
                .setPosition(position)
                .setReleasedQi(BodyProtobuf.toInventory(qi))
                .build();
    }

    private static HumanoidWorldState queued(RegionRelease release) {
        // 按生产存档格式恢复一笔独立队列，重试仍走实际 Humanoid 与 Human 接线。
        var pending = new NbtList();
        pending.add(new NbtByteArray(release.toByteArray()));
        var nbt = new NbtCompound();
        nbt.putLong("next_sequence", 1);
        nbt.put("pending", pending);
        nbt.put("pending_forget", new NbtList());
        return HumanoidWorldState.read(nbt);
    }

    private static void later(TestContext context, CheckedAction action) {
        // 留出受理、tick 末合并保存与下一轮重试的时间，异常仍使测试失败。
        context.waitAndRun(HANDOFF_TICKS, () -> {
            try {
                action.run();
            } catch (Exception failure) {
                throw new IllegalStateException("释放交接回归失败：" + failure, failure);
            }
        });
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }

    private static Field binding() throws ReflectiveOperationException {
        // 仅测试替换公开入口的响应传输，不读取或改写 Human 私有账本。
        Object endpoint = FabricLoader.getInstance().getEntrypoints("meridian_environment_host", Object.class).get(0);
        Field field = endpoint.getClass().getDeclaredField("delegate");
        field.setAccessible(true /* allowAccess */);
        return field;
    }

    private static EnvironmentHostResponse exchange(RegionRelease release) throws Exception {
        // 失败码必须来自注册的真实字节入口，不能直接调用接收端内部方法代替。
        Object endpoint = FabricLoader.getInstance().getEntrypoints("meridian_environment_host", Object.class).get(0);
        var request = EnvironmentHostRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(UUID.randomUUID().toString())
                .setRelease(release)
                .build();
        byte[] response = (byte[]) endpoint.getClass().getMethod("exchange", byte[].class)
                .invoke(endpoint, (Object) request.toByteArray());
        return EnvironmentHostResponse.parseFrom(response);
    }

    private static byte[] acceptedResponse(byte[] payload, ReleaseResult result) {
        // 注入只有受理语义的响应，保持正常的协议版本和请求关联。
        try {
            var request = EnvironmentHostRequest.parseFrom(payload);
            return EnvironmentHostResponse.newBuilder()
                    .setProtocolVersion(1)
                    .setRequestId(request.getRequestId())
                    .setReleased(result)
                    .build()
                    .toByteArray();
        } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
            throw new IllegalArgumentException("测试请求无法解码", invalid);
        }
    }

    private static byte[] discardResponse(Function<byte[], byte[]> delegate, byte[] payload) {
        // 接收端先完成真实调用，再在返回调用方前断开响应。
        delegate.apply(payload);
        throw new IllegalStateException("注入释放响应丢失");
    }

    private static void assertOneDurableCredit(TestContext context, RegionRelease release) throws Exception {
        // 读取真实正式文件，验证未完成交接的去重事实与待入账数量各只有一笔。
        NbtCompound saved = NbtIo.readCompressed(AuditReproSupport.worldFile(context,
                AuditReproSupport.HUMAN_FILE).toFile()).getCompound("data");
        NbtList ids = saved.getList("release_ids", NbtElement.COMPOUND_TYPE);
        assertOneSavedRequest(context, release, ids);
        NbtList credits = saved.getList("pending_credits", NbtElement.COMPOUND_TYPE);
        assertOneSavedCredit(context, release, credits);
    }

    private static void assertOneSavedRequest(TestContext context, RegionRelease release, NbtList ids)
            throws Exception {
        // 去重项必须保存完整原请求，重复发送不能扩增相同 ID。
        int matchingIds = 0;
        for (int index = 0; index < ids.size(); index++) {
            var request = RegionRelease.parseFrom(ids.getCompound(index).getByteArray("request"));
            if (request.getReleaseId().equals(release.getReleaseId())) {
                context.assertTrue(request.equals(release), "保存的原请求内容改变");
                matchingIds++;
            }
        }
        context.assertTrue(matchingIds == 1, "重复重试产生多笔去重事实");
    }

    private static void assertOneSavedCredit(TestContext context, RegionRelease release, NbtList credits) {
        // 待入账明细必须与原库存完全一致，不能因重复响应丢失再次排队。
        int matchingCredits = 0;
        for (int index = 0; index < credits.size(); index++) {
            NbtCompound credit = credits.getCompound(index);
            if (credit.getString("release_id").equals(release.getReleaseId())) {
                context.assertTrue(Arrays.equals(credit.getByteArray("qi"), release.getReleasedQi().toByteArray()),
                        "待入账库存与原请求不同");
                matchingCredits++;
            }
        }
        context.assertTrue(matchingCredits == 1, "重复重试产生多笔入账记录");
    }
}
