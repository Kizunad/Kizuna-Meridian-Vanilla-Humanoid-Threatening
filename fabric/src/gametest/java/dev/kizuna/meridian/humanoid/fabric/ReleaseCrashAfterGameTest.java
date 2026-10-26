package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.WorldSavePath;

public final class ReleaseCrashAfterGameTest implements FabricGameTest {
    private static final int CREDIT_AND_FORGET_TICKS = 610;
    private static final int TEST_LIMIT = 750;

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = TEST_LIMIT)
    public void audit05RestartRetainsOneDurableOwner(TestContext context) throws Exception {
        // 新进程恢复同一世界，确认服务器真正读取了强制退出前的两个文件。
        NbtCompound marker = NbtIo.readCompressed(
                AuditReproSupport.evidence(AuditReproSupport.RELEASE_MARKER_FILE).toFile());
        var release = EnvironmentHostMessages.RegionRelease.parseFrom(marker.getByteArray("release"));
        String releaseId = release.getReleaseId();
        var sender = AuditReproSupport.sender();
        var human = AuditReproSupport.human();
        var savedSender = sender.writeNbt(new NbtCompound());
        var savedHuman = human.writeNbt(new NbtCompound());
        String world = context.getWorld().getServer().getSavePath(WorldSavePath.ROOT)
                .toAbsolutePath().normalize().toString();
        context.assertTrue(world.equals(marker.getString("world")), "重启没有使用同一世界：" + world);
        context.assertTrue(savedSender.getLong("next_sequence") == marker.getLong("next_sequence"),
                "发送账本没有恢复强制退出前的序号");
        context.assertTrue(savedHuman.getString("epoch").equals(marker.getString("human_epoch")),
                "接收端没有恢复强制退出前的时钟世代");
        boolean sourceOwns = AuditReproSupport.pending(sender).stream()
                .anyMatch(value -> value.getReleaseId().equals(releaseId));
        boolean targetOwns = AuditReproSupport.humanHasRelease(human, releaseId);
        String evidence = "REPRO-A-05-RESTART release=" + releaseId
                + " expected=at-least-one-durable-owner actual=sender:" + sourceOwns + ",receiver:" + targetOwns;
        System.out.println(evidence);
        context.assertTrue(sourceOwns || targetOwns, evidence);
        context.assertTrue(targetOwns && AuditReproSupport.humanFileHasRelease(context, releaseId),
                "重启后未恢复持久化确认的接收事实");
        context.assertTrue(!sourceOwns && savedSender.getList("pending_forget",
                net.minecraft.nbt.NbtElement.STRING_TYPE).contains(net.minecraft.nbt.NbtString.of(releaseId)),
                "没有从磁盘恢复待遗忘事实");
        context.waitAndRun(CREDIT_AND_FORGET_TICKS, () -> verifyReclaimed(context, releaseId));
    }

    private static void verifyReclaimed(TestContext context, String releaseId) {
        // 新进程靠正常 tick 重试完成入账和遗忘，两份正式文件都不能积累已完成历史。
        try {
            var senderFile = NbtIo.readCompressed(AuditReproSupport.worldFile(context,
                    AuditReproSupport.SENDER_FILE).toFile()).getCompound("data");
            context.assertTrue(!senderFile.getList("pending_forget", net.minecraft.nbt.NbtElement.STRING_TYPE)
                    .contains(net.minecraft.nbt.NbtString.of(releaseId)), "发送文件未回收待遗忘 ID");
            context.assertTrue(!AuditReproSupport.humanFileHasRelease(context, releaseId),
                    "接收文件未回收已完成去重项");
            System.out.println("REPRO-A-05-RECLAIM release=" + releaseId + " sender=false receiver=false");
            context.complete();
        } catch (Exception failure) {
            throw new IllegalStateException("强退恢复后的遗忘失败", failure);
        }
    }
}
