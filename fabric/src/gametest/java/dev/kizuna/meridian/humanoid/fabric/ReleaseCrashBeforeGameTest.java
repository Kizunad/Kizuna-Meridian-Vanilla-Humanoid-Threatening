package dev.kizuna.meridian.humanoid.fabric;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

import java.util.UUID;

public final class ReleaseCrashBeforeGameTest implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void audit05HaltAfterSenderSaveBeforeReceiverSave(TestContext context) throws Exception {
        // 保存旧接收端、持久发送队列，再完成真实交接并只保存队列删除。
        var entity = AuditReproSupport.villager(context, UUID.randomUUID());
        var response = AuditReproSupport.inspect(entity);
        context.assertTrue(response.hasBody(), "无法创建待释放身体：" + response);
        var sender = AuditReproSupport.sender();
        var human = AuditReproSupport.human();
        entity.remove(Entity.RemovalReason.DISCARDED);
        var releases = AuditReproSupport.pending(sender);
        int releaseCount = releases.size();
        context.assertTrue(releaseCount == 1, "崩溃夹具要求只有一笔待释放记录：" + releaseCount);
        var release = releases.get(0);
        String releaseId = release.getReleaseId();
        var senderFile = AuditReproSupport.worldFile(context, AuditReproSupport.SENDER_FILE);
        AuditReproSupport.save(human, AuditReproSupport.worldFile(context, AuditReproSupport.HUMAN_FILE));
        AuditReproSupport.save(sender, senderFile);
        sender.retry();
        context.assertTrue(AuditReproSupport.humanHasRelease(human, releaseId),
                "真实 Human 接收端未在内存受理释放");
        AuditReproSupport.save(sender, senderFile);
        writeMarker(context, release);
        int pendingCount = AuditReproSupport.pending(sender).size();
        System.out.println("REPRO-A-05-HALT release=" + releaseId
                + " senderPending=" + pendingCount + " receiverInMemory=true receiverSavedAfterAccept=false exit="
                + AuditReproSupport.CRASH_EXIT_CODE);
        System.out.flush();
        Runtime.getRuntime().halt(AuditReproSupport.CRASH_EXIT_CODE);
    }

    private static void writeMarker(TestContext context,
                                    dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.RegionRelease release)
            throws Exception {
        // 保存世界身份和请求原文，使重启测试能排除错误世界或新建账本的假阳性。
        var sender = AuditReproSupport.sender();
        var human = AuditReproSupport.human();
        NbtCompound marker = new NbtCompound();
        marker.putByteArray("release", release.toByteArray());
        marker.putLong("next_sequence", sender.writeNbt(new NbtCompound()).getLong("next_sequence"));
        marker.putString("human_epoch", human.writeNbt(new NbtCompound()).getString("epoch"));
        marker.putString("world", context.getWorld().getServer().getSavePath(
                net.minecraft.util.WorldSavePath.ROOT).toAbsolutePath().normalize().toString());
        AuditReproSupport.write(marker, AuditReproSupport.evidence(AuditReproSupport.RELEASE_MARKER_FILE));
    }
}
