package dev.kizuna.meridian.humanoid.fabric;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import java.util.UUID;

import static dev.kizuna.meridian.humanoid.fabric.HumanoidGameTestTicks.DEFAULT_TIMEOUT;

public final class ReleaseCrashBeforeGameTest {
    private static final int HANDOFF_TICKS = 4;
    private static final int CRASH_REGION_SPACING_BLOCKS = 16_384;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void audit05HaltAfterDurableHandoffBeforeWorldSave(GameTestHelper context) throws Exception {
        // 保存旧接收端与发送队列，交接后只显式保存发送端，再跳过正常关服直接强退。
        var entity = AuditReproSupport.villager(context, UUID.randomUUID());
        var response = AuditReproSupport.inspect(entity);
        context.assertTrue(response.hasBody(), "无法创建待释放身体：" + response);
        var sender = AuditReproSupport.sender();
        var human = AuditReproSupport.human();
        // GameTest 重启会重置世界时钟；每轮选择新区域，避免旧窗口决定本轮等待时长。
        long sequence = sender.save(new CompoundTag()).getLongOr("next_sequence", 0);
        long blockX = Math.multiplyExact(sequence + 1, CRASH_REGION_SPACING_BLOCKS);
        entity.setPos(blockX, entity.getY(), 0);
        entity.remove(Entity.RemovalReason.DISCARDED);
        var releases = AuditReproSupport.pending(sender);
        int releaseCount = releases.size();
        context.assertTrue(releaseCount == 1, "崩溃夹具要求只有一笔待释放记录：" + releaseCount);
        var release = releases.get(0);
        var senderFile = AuditReproSupport.worldFile(context, AuditReproSupport.SENDER_FILE);
        AuditReproSupport.save(human, AuditReproSupport.worldFile(context, AuditReproSupport.HUMAN_FILE));
        AuditReproSupport.save(sender, senderFile);
        sender.retry();
        context.assertTrue(!AuditReproSupport.pending(sender).isEmpty(), "首次受理不应即时确认");
        context.runAfterDelay(HANDOFF_TICKS, () -> {
            try {
                haltAfterHandoff(context, release);
            } catch (Exception failure) {
                throw new IllegalStateException("强退夹具交接失败", failure);
            }
        });
    }

    private static void haltAfterHandoff(GameTestHelper context,
            dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.RegionRelease release) throws Exception {
        // tick 末接收端已提交，发送端再持久保存删除和待遗忘 ID，然后跳过关服保存。
        var sender = AuditReproSupport.sender();
        var human = AuditReproSupport.human();
        String releaseId = release.getReleaseId();
        sender.retry();
        context.assertTrue(AuditReproSupport.humanHasRelease(human, releaseId),
                "真实 Human 接收端未在内存受理释放");
        context.assertTrue(AuditReproSupport.humanFileHasRelease(context, releaseId),
                "Human 返回确认时正式文件尚未包含释放 ID");
        context.assertTrue(AuditReproSupport.pending(sender).isEmpty(), "持久化确认后发送端没有删除队列");
        var senderFile = AuditReproSupport.worldFile(context, AuditReproSupport.SENDER_FILE);
        AuditReproSupport.save(sender, senderFile);
        context.assertTrue(sender.save(new CompoundTag())
                .getListOrEmpty("pending_forget").contains(
                        net.minecraft.nbt.StringTag.valueOf(releaseId)), "没有持久保留待遗忘 ID");
        writeMarker(context, release);
        int pendingCount = AuditReproSupport.pending(sender).size();
        System.out.println("REPRO-A-05-HALT release=" + releaseId
                + " senderPending=" + pendingCount
                + " senderForgetSaved=true receiverInMemory=true receiverSavedAfterAccept=true exit="
                + AuditReproSupport.CRASH_EXIT_CODE);
        System.out.flush();
        Runtime.getRuntime().halt(AuditReproSupport.CRASH_EXIT_CODE);
    }

    private static void writeMarker(GameTestHelper context,
                                    dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.RegionRelease release)
            throws Exception {
        // 保存世界身份和请求原文，使重启测试能排除错误世界或新建账本的假阳性。
        var sender = AuditReproSupport.sender();
        var human = AuditReproSupport.human();
        CompoundTag marker = new CompoundTag();
        marker.putByteArray("release", release.toByteArray());
        marker.putLong("next_sequence", sender.save(new CompoundTag()).getLongOr("next_sequence", 0));
        marker.putString("human_epoch", human.snapshot().getStringOr("epoch", ""));
        marker.putString("world", context.getLevel().getServer().getWorldPath(
                net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize().toString());
        AuditReproSupport.write(marker, AuditReproSupport.evidence(AuditReproSupport.RELEASE_MARKER_FILE));
    }
}
