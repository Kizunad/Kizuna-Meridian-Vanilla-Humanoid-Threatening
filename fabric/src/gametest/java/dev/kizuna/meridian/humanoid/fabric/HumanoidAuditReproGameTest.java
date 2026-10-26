package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.protocol.pb.MeridianMessages;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

public final class HumanoidAuditReproGameTest implements FabricGameTest {
    private static final long ADVANCE_TICKS = 2;
    private static final UUID RECEIPT_ENTITY_ID = UUID.fromString("5e8d8f0b-1f5d-41eb-9dc3-f403ccffec9c");

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void audit01ClockAdvancesAfterBirth(TestContext context) {
        // 跨真实服务器 tick 查询同一身体，目标应跟随 Human 世界时钟。
        VillagerEntity entity = AuditReproSupport.villager(context, UUID.randomUUID());
        var initial = AuditReproSupport.inspect(entity);
        context.assertTrue(initial.hasBody(), "初始身体未创建：" + initial);
        long initialMillis = initial.getBody().getSettledAt().getElapsedMillis();
        context.waitAndRun(ADVANCE_TICKS, () -> {
            long expected = HumanoidFabric.now().getElapsedMillis();
            var response = AuditReproSupport.inspect(entity);
            var state = ((HumanoidEntityAccess) entity).meridian$humanoidBody();
            long actual = state.snapshot().getSettledAt().getElapsedMillis();
            context.assertTrue(expected > initialMillis, "服务器时钟没有实际推进");
            String evidence = "REPRO-A-01 expected=" + expected + " actual=" + actual
                    + " initial=" + initialMillis + " response=" + response.getResultCase()
                    + " failure=" + response.getFailure().getCode();
            System.out.println(evidence);
            context.assertTrue(response.hasBody() && actual == expected, evidence);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void audit02NewestReceiptSurvivesEntityNbtRoundTrip(TestContext context) {
        // 超过 32 次真实健康提交后恢复实体，再重放最后一笔成功请求。
        VillagerEntity entity = AuditReproSupport.villager(context, RECEIPT_ENTITY_ID);
        var batch = AuditReproSupport.commitReceipts(context, entity);
        NbtCompound saved = entity.writeNbt(new NbtCompound());
        var revisions = AuditReproSupport.savedRevisions(saved.getCompound(AuditReproSupport.BODY_TAG));
        context.assertTrue(revisions.size() == AuditReproSupport.MAX_RECEIPTS, "回执窗口数量不是 32");
        long firstRevision = batch.body().getRevision() - AuditReproSupport.MAX_RECEIPTS + 1;
        for (int index = 0; index < revisions.size(); index++) {
            context.assertTrue(revisions.get(index) == firstRevision + index, "回执窗口遗漏最新提交：" + revisions);
        }
        var access = (HumanoidEntityAccess) entity;
        access.meridian$humanoidBody(null);
        entity.readNbt(saved);
        var restored = AuditReproSupport.inspect(entity);
        context.assertTrue(restored.hasBody(), "恢复身体失败：" + restored);
        var requests = batch.requests();
        var last = requests.get(requests.size() - 1);
        var replay = AuditReproSupport.exchange(last);
        String evidence = "REPRO-A-02 revision=" + batch.body().getRevision() + " retained=" + revisions
                + " expected=health-success actual=" + replay.getResultCase() + " " + replay.getFailure().getCode();
        System.out.println(evidence);
        context.assertTrue(replay.hasBody() && replay.hasHealth(), evidence);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void audit03WorldQueueRejectsWrongElementType(TestContext context) {
        // 真正使用 Minecraft NBT 列表，错误类型必须在空值兜底前被拒绝。
        NbtList wrong = new NbtList();
        wrong.add(NbtString.of("not-a-release"));
        NbtCompound saved = new NbtCompound();
        saved.putLong("next_sequence", 1);
        saved.put("pending", wrong);
        try {
            HumanoidWorldState restored = HumanoidWorldState.read(saved);
            var entries = restored.writeNbt(new NbtCompound())
                    .getList("pending", NbtElement.BYTE_ARRAY_TYPE);
            String evidence = "REPRO-A-03-world expected=IllegalArgumentException actual=pending-count:"
                    + entries.size();
            System.out.println(evidence);
            context.throwGameTestException(evidence);
        } catch (IllegalArgumentException expected) {
            context.assertTrue(saved.get("pending").equals(wrong), "拒绝坏队列时改写了原始 NBT");
            context.complete();
        }
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void audit03EntityReceiptsRejectWrongElementType(TestContext context) throws IOException {
        // 坏回执隔离身体功能，实体仍可保存完整原始标签，不能影响区块保存。
        VillagerEntity entity = AuditReproSupport.villager(context, UUID.randomUUID());
        context.assertTrue(AuditReproSupport.inspect(entity).hasBody(), "初始身体未创建");
        NbtCompound saved = entity.writeNbt(new NbtCompound());
        NbtList wrong = new NbtList();
        wrong.add(NbtString.of("not-a-receipt"));
        NbtCompound original = saved.getCompound(AuditReproSupport.BODY_TAG);
        original.put("receipts", wrong);
        original.putString("unknown_field", "原始附属数据");
        byte[] originalBytes = encodeNbt(original);

        entity.readNbt(saved);
        assertBodyLoadFailure(context, entity);
        original.putString("copy_probe", "修改输入不应改变保留副本");
        NbtCompound resaved = assertOriginalBodySaved(context, entity, originalBytes);
        assertBodyLoadFailure(context, entity);

        // 保存输出与保留副本也必须隔离；再次读入坏档仍保持失败，日志不应重复。
        resaved.getCompound(AuditReproSupport.BODY_TAG).putString("copy_probe", "修改保存输出");
        NbtCompound secondSave = assertOriginalBodySaved(context, entity, originalBytes);
        entity.readNbt(secondSave);
        assertBodyLoadFailure(context, entity);

        // 将坏档实体留在世界中执行完整保存，身体错误不能中断服务器持久化。
        context.getWorld().getServer().save(false /* suppressLogs */, true /* flush */, false /* force */);
        assertOriginalBodySaved(context, entity, originalBytes);
        System.out.println("NBT-PRESERVE entity=" + entity.getUuidAsString() + " bytes=" + originalBytes.length);
        context.complete();
    }

    private static void assertBodyLoadFailure(TestContext context, VillagerEntity entity) {
        // 已认领实体的坏档始终返回宿主错误，不能变成未绑定或创建新身体。
        var response = AuditReproSupport.inspect(entity);
        context.assertTrue(response.hasFailure()
                        && response.getFailure().getCode() == MeridianMessages.FailureCode.INTERNAL_ERROR,
                "坏身体存档未返回 INTERNAL_ERROR：" + response);
        context.assertTrue(((HumanoidEntityAccess) entity).meridian$humanoidBody() == null,
                "坏身体存档仍在使用原来的内存身体");
    }

    private static NbtCompound assertOriginalBodySaved(TestContext context, VillagerEntity entity, byte[] expected)
            throws IOException {
        // 经过真实实体保存与 Mixin，比较完整标签及编码字节，包括未知字段和错误类型。
        NbtCompound saved = entity.writeNbt(new NbtCompound());
        NbtCompound actualBody = saved.getCompound(AuditReproSupport.BODY_TAG);
        byte[] actual = encodeNbt(actualBody);
        NbtCompound expectedBody = NbtIo.read(new DataInputStream(new ByteArrayInputStream(expected)));
        context.assertTrue(expectedBody.equals(actualBody), "实体保存改写了原始坏身体标签内容");
        context.assertTrue(Arrays.equals(expected, actual), "实体保存改写了原始坏身体标签");
        return saved;
    }

    private static byte[] encodeNbt(NbtCompound nbt) throws IOException {
        // 复合标签不定义键顺序；复制统一映射布局后比较编码，字段类型与载荷仍须逐字节相同。
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            NbtIo.write(nbt.copy(), output);
        }
        return bytes.toByteArray();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void emptyQueueAndReceiptListsRemainValid(TestContext context) {
        // 空 NBT 列表没有元素类型，仍是合法空状态，不能被严格类型校验误拒绝。
        NbtCompound empty = new NbtCompound();
        empty.putLong("next_sequence", 0);
        empty.put("pending", new NbtList());
        var state = HumanoidWorldState.read(empty);
        context.assertTrue(state.writeNbt(new NbtCompound()).equals(empty), "空释放队列无法往返");

        VillagerEntity entity = AuditReproSupport.villager(context, UUID.randomUUID());
        context.assertTrue(AuditReproSupport.inspect(entity).hasBody(), "无法创建空回执身体");
        NbtCompound saved = entity.writeNbt(new NbtCompound());
        saved.getCompound(AuditReproSupport.BODY_TAG).put("receipts", new NbtList());
        ((HumanoidEntityAccess) entity).meridian$humanoidBody(null);
        entity.readNbt(saved);
        context.assertTrue(AuditReproSupport.inspect(entity).hasBody(), "合法空回执无法恢复身体");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void prepareExistingBodyForBadLedgerStartup(TestContext context) throws Exception {
        // 从健康运行保存真实实体与 Human 时钟，供下一次坏档启动恢复。
        VillagerEntity entity = AuditReproSupport.villager(context, UUID.randomUUID());
        var response = AuditReproSupport.inspect(entity);
        context.assertTrue(response.hasBody(), "无法准备已有身体：" + response);
        AuditReproSupport.write(entity.writeNbt(new NbtCompound()),
                AuditReproSupport.evidence(AuditReproSupport.FIXTURE_ENTITY_FILE));
        AuditReproSupport.save(AuditReproSupport.human(),
                AuditReproSupport.evidence(AuditReproSupport.FIXTURE_HUMAN_FILE));
        System.out.println("REPRO-A-04-FIXTURE body=" + response.getBody().getId());
        context.complete();
    }
}
