package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.LevelResource;

public final class ReleaseCrashAfterGameTest {
    private static final int CREDIT_AND_FORGET_TICKS = 610;
    private static final int TEST_LIMIT = 750;

    @GameTest(maxTicks = TEST_LIMIT)
    public void audit05RestartRetainsOneDurableOwner(GameTestHelper context) throws Exception {
        // 新进程恢复同一世界，确认服务器真正读取了强制退出前的两个文件；缺字段时按旧读法取默认值，断言照常失败。
        CompoundTag marker = AuditReproSupport.read(
                AuditReproSupport.evidence(AuditReproSupport.RELEASE_MARKER_FILE));
        var release = EnvironmentHostMessages.RegionRelease.parseFrom(
                marker.getByteArray("release").orElse(new byte[0]));
        String releaseId = release.getReleaseId();
        var sender = AuditReproSupport.sender();
        var human = AuditReproSupport.human();
        var savedSender = sender.save(new CompoundTag());
        var savedHuman = human.snapshot();
        String world = context.getLevel().getServer().getWorldPath(LevelResource.ROOT)
                .toAbsolutePath().normalize().toString();
        context.assertTrue(world.equals(marker.getStringOr("world", "")), "重启没有使用同一世界：" + world);
        context.assertTrue(savedSender.getLongOr("next_sequence", 0) == marker.getLongOr("next_sequence", 0),
                "发送账本没有恢复强制退出前的序号");
        context.assertTrue(savedHuman.getStringOr("epoch", "").equals(marker.getStringOr("human_epoch", "")),
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
        context.assertTrue(!sourceOwns && savedSender.getListOrEmpty("pending_forget")
                .contains(net.minecraft.nbt.StringTag.valueOf(releaseId)),
                "没有从磁盘恢复待遗忘事实");
        context.runAfterDelay(CREDIT_AND_FORGET_TICKS, () -> verifyReclaimed(context, releaseId));
    }

    private static void verifyReclaimed(GameTestHelper context, String releaseId) {
        // 新进程靠正常 tick 重试完成入账和遗忘，两份正式文件都不能积累已完成历史。
        try {
            var senderFile = AuditReproSupport.read(AuditReproSupport.worldFile(context,
                    AuditReproSupport.SENDER_FILE)).getCompoundOrEmpty("data");
            context.assertTrue(!senderFile.getListOrEmpty("pending_forget")
                    .contains(net.minecraft.nbt.StringTag.valueOf(releaseId)), "发送文件未回收待遗忘 ID");
            context.assertTrue(!AuditReproSupport.humanFileHasRelease(context, releaseId),
                    "接收文件未回收已完成去重项");
            System.out.println("REPRO-A-05-RECLAIM release=" + releaseId + " sender=false receiver=false");
            context.succeed();
        } catch (Exception failure) {
            throw new IllegalStateException("强退恢复后的遗忘失败", failure);
        }
    }
}
