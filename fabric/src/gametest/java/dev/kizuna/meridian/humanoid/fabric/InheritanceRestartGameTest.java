package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.human.fabric.HumanGameTestAccess;
import dev.kizuna.meridian.human.fabric.HumanInheritance;
import dev.kizuna.meridian.server.BodyExports;
import java.io.IOException;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

public final class InheritanceRestartGameTest {
    private enum Checkpoint { EXPORTED, OPENED }
    private static final int OUTSIDE_STRUCTURE = 96;
    private static final int LOAD_TIMEOUT = 200;

    @GameTest(maxTicks = LOAD_TIMEOUT)
    public void restartAfterExportKeepsOnlyOriginalFunding(GameTestHelper context) throws IOException {
        // 两个进程分别留下实扣与恢复该案。
        checkpoint(context, Checkpoint.EXPORTED);
    }

    @GameTest(maxTicks = LOAD_TIMEOUT)
    public void restartAfterOpeningCreditsOnlyTheOriginalMeridian(GameTestHelper context) throws IOException {
        // 第一个进程停在已开未入账，另一个进程登录同一玩家。
        checkpoint(context, Checkpoint.OPENED);
    }

    private static void checkpoint(GameTestHelper context, Checkpoint point) throws IOException {
        // 标记只保存身份、区块与断言基线，资金与回执必须从游戏存档读取。
        var marker = AuditReproSupport.worldFile(context, "inheritance-" + point + ".nbt");
        if ("inheritance-save".equals(System.getProperty("meridian.repro.mode"))) {
            var record = save(context, point);
            AuditReproSupport.write(record, marker);
            context.succeed();
        } else {
            load(context, AuditReproSupport.read(marker), point);
        }
    }

    private static CompoundTag save(GameTestHelper context, Checkpoint point) {
        // 移到原版测试结构清理范围之外，确保第二进程恢复原实体和同一具身体。
        var level = context.getLevel();
        var elder = InheritanceGameTest.elder(context);
        var position = context.absolutePos(new BlockPos(OUTSIDE_STRUCTURE, 1, 1));
        var chunk = ChunkPos.containing(position);
        level.setChunkForced(chunk.x(), chunk.z(), true /* forced */);
        level.getChunkAt(position);
        var donor = new HumanoidDonations(elder.getUUID());
        var operation = UUID.randomUUID();
        var record = new CompoundTag();
        try (var student = CrisisTestPlayer.create(context)) {
            var player = student.player();
            var before = InheritanceGameTest.studentBody(player);
            var quote = HumanInheritance.preview(player, operation, donor)
                    .orElseThrow(() -> new AssertionError("在场老人和学生未能取得报价"));
            var funded = InheritanceGameTest.await(context, donor.record(quote));
            record.putString("operation", operation.toString());
            record.putString("elder", elder.getStringUUID());
            record.putString("student", player.getStringUUID());
            record.putString("target", funded.getTarget());
            record.putLong("chunk", chunk.pack());
            record.putLong("saved_tick", level.getServer().overworld().getGameTime());
            record.putLong("opened", InheritanceGameTest.opened(before));
            record.putDouble("student_qi", before.totalQi());
            record.putDouble("remainder", donor.inspect().orElseThrow().totalQi());
            if (point == Checkpoint.OPENED) {
                HumanGameTestAccess.openFundedMeridian(player, funded);
                var opened = InheritanceGameTest.studentBody(player);
                context.assertTrue(InheritanceGameTest.opened(opened) == InheritanceGameTest.opened(before) + 1
                        && Math.abs(opened.totalQi() - before.totalQi()) < InheritanceGameTest.TOLERANCE,
                        "保存点不在已开未入账");
            }
        }
        elder.setPos(Vec3.atBottomCenterOf(position));
        level.getServer().saveAllChunks(false /* suppressLogs */, true /* flush */, true /* force */);
        return record;
    }

    private static void load(GameTestHelper context, CompoundTag record, Checkpoint point) {
        // 等原区块加载，再用原人、原案登录；读取回执亦不能造成第二次开脉。
        var level = context.getLevel();
        var chunk = ChunkPos.unpack(record.getLong("chunk").orElseThrow());
        level.setChunkForced(chunk.x(), chunk.z(), true /* forced */);
        level.getChunk(chunk.x(), chunk.z());
        var elder = UUID.fromString(record.getString("elder").orElseThrow());
        var donor = new HumanoidDonations(elder);
        var operation = UUID.fromString(record.getString("operation").orElseThrow());
        context.startSequence().thenWaitUntil(() -> {
            // GameTest 专服重开时测试时钟可能回到起点。原账号只能在保存游标之后正常登录。
            context.assertTrue(level.getEntity(elder) != null, "老人原区块尚未加载");
            long savedTick = record.getLong("saved_tick").orElseThrow();
            context.assertTrue(level.getServer().overworld().getGameTime() > savedTick, "尚未到登录游标");
        }).thenExecute(() -> {
            var funded = donor.lookup(operation).orElseThrow();
            context.assertTrue(BodyExports.pending(funded), "恢复前不在已扣未结阶段");
            var studentId = UUID.fromString(record.getString("student").orElseThrow());
            try (var student = CrisisTestPlayer.create(context, studentId)) {
                var player = student.player();
                var existing = InheritanceGameTest.await(context, HumanInheritance.receipt(player, operation));
                context.assertTrue(existing.isPresent() == (point == Checkpoint.OPENED), "载入的受方阶段不符");
                var paid = InheritanceGameTest.await(context, HumanInheritance.accept(player, operation, donor))
                        .orElseThrow();
                var repeat = InheritanceGameTest.await(context, HumanInheritance.accept(player, operation, donor))
                        .orElseThrow();
                var after = InheritanceGameTest.studentBody(player);
                String target = record.getString("target").orElseThrow();
                context.assertTrue(paid.equals(repeat) && target.equals(paid.getTarget()),
                        "重放换了原目标或回执");
                context.assertTrue(InheritanceGameTest.opened(after) == record.getLong("opened").orElseThrow() + 1,
                        "重启后不止开一脉");
                double gained = after.totalQi() - record.getDouble("student_qi").orElseThrow();
                context.assertTrue(paid.getCreditedQi() > 0 && Math.abs(gained - paid.getCreditedQi())
                        < InheritanceGameTest.TOLERANCE, "受方重复或缺少入账");
                double remaining = donor.inspect().orElseThrow().totalQi();
                context.assertTrue(Math.abs(remaining - record.getDouble("remainder").orElseThrow())
                        < InheritanceGameTest.TOLERANCE, "恢复时重复扣老人");
                context.assertTrue(!BodyExports.pending(donor.lookup(operation).orElseThrow()), "供方未结案");
            }
            level.setChunkForced(chunk.x(), chunk.z(), false /* forced */);
        }).thenSucceed();
    }
}
