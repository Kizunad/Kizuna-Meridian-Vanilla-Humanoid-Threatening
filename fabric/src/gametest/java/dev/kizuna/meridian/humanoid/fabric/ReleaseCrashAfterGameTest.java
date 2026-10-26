package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.WorldSavePath;

public final class ReleaseCrashAfterGameTest implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
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
        context.complete();
    }
}
