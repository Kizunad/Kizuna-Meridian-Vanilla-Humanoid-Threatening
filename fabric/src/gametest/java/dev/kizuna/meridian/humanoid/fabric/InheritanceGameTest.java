package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.MeridianOpening;
import dev.kizuna.meridian.human.fabric.HumanInheritance;
import dev.kizuna.meridian.human.server.InheritanceDonor;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.LumenExport;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;
import dev.kizuna.meridian.server.BodyExports;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

public final class InheritanceGameTest {
    static final double TOLERANCE = 1e-7;
    private static final double DONOR_AMOUNT = 4;

    @GameTest(maxTicks = HumanoidGameTestTicks.DEFAULT_TIMEOUT)
    public void liveOfferDebitsActualElderAndReplaysSameReceipt(GameTestHelper context) {
        // 不预先代扣，实际确认入口完成导出、开脉、收款与供方销账。
        var elder = elder(context);
        var donor = new HumanoidDonations(elder.getUUID());
        try (var student = CrisisTestPlayer.create(context)) {
            var player = student.player();
            UUID operation = UUID.randomUUID();
            var offer = HumanInheritance.preview(player, operation, donor).orElseThrow();
            var original = donor.inspect().orElseThrow();
            var before = studentBody(player);
            var paid = await(context, HumanInheritance.accept(player, operation, donor)).orElseThrow();
            var replay = await(context, HumanInheritance.accept(player, operation, donor)).orElseThrow();
            var after = studentBody(player);
            double actualDebit = original.totalQi() - donor.inspect().orElseThrow().totalQi();
            context.assertTrue(actualDebit > 0 && Math.abs(paid.getCreditedQi() - actualDebit) < TOLERANCE,
                    "收入与老人实扣不符");
            context.assertTrue(paid.equals(replay) && paid.getTarget().equals(offer.getTarget()), "重放换了回执");
            context.assertTrue(opened(after) == opened(before) + 1, "未恰开一脉");
            context.assertTrue(Math.abs(after.totalQi() - before.totalQi() - actualDebit) < TOLERANCE,
                    "重复领取或漏收");
        } finally {
            elder.discard();
        }
        context.succeed();
    }

    @GameTest(maxTicks = HumanoidGameTestTicks.DEFAULT_TIMEOUT)
    public void dyingDonorReleasesOnlyRemainderAndStudentCanClaimOnce(GameTestHelper context) throws Exception {
        // 扣款后、开脉前让老人死亡，环境账只收身上余量，原案仍能从世界登记继续。
        var elder = elder(context);
        try (var student = CrisisTestPlayer.create(context)) {
            var player = student.player();
            var donor = new HumanoidDonations(elder.getUUID());
            UUID operation = UUID.randomUUID();
            var before = studentBody(player);
            var original = donor.inspect().orElseThrow();
            var quote = HumanInheritance.preview(player, operation, donor).orElseThrow();
            var funded = await(context, donor.record(quote));
            var remainder = donor.inspect().orElseThrow();
            double exported = original.totalQi() - remainder.totalQi();
            context.assertTrue(exported > 0, "老人没有实扣");
            elder.remove(Entity.RemovalReason.KILLED);
            double released = AuditReproSupport.pending(HumanoidFabric.worldState()).stream()
                    .filter(entry -> entry.getReleaseId().startsWith("meridian_humanoid:" + elder.getUUID() + ":"))
                    .mapToDouble(entry -> BodyProtobuf.fromInventory(entry.getReleasedQi()).totalQi()).sum();
            context.assertTrue(Math.abs(remainder.totalQi() - released) < TOLERANCE, "老人死时重复释放了在途货");
            context.assertTrue(donor.lookup(operation).orElseThrow().equals(funded), "老人死后在途货丢失");
            var first = await(context, HumanInheritance.accept(player, operation, donor)).orElseThrow();
            var second = await(context, HumanInheritance.accept(player, operation, donor)).orElseThrow();
            var after = studentBody(player);
            context.assertTrue(first.equals(second) && first.getCreditedQi() > 0, "重复点击改变回执");
            context.assertTrue(opened(after) == opened(before) + 1, "同案开脉数不等于一");
            context.assertTrue(Math.abs(original.totalQi() + before.totalQi() - released - after.totalQi())
                    < TOLERANCE, "供受双方与环境总量不守恒");
            context.assertTrue(!BodyExports.pending(donor.lookup(operation).orElseThrow()), "结案仍有在途货");
        }
        context.succeed();
    }

    @GameTest(maxTicks = HumanoidGameTestTicks.DEFAULT_TIMEOUT)
    public void pendingDonorWriteAllowsTicksAndConcurrentClaimsShareReceipt(GameTestHelper context) {
        // 用受控回执延迟第一次真实写入的确认；下一 tick 到来时两次点击都不能早开脉或报告成功。
        var elder = elder(context);
        var student = CrisisTestPlayer.create(context);
        var player = student.player();
        var donor = new DelayedDonor(new HumanoidDonations(elder.getUUID()), new CompletableFuture<>());
        var operation = UUID.randomUUID();
        long openedBefore = opened(studentBody(player));
        var first = HumanInheritance.accept(player, operation, donor);
        var second = HumanInheritance.accept(player, operation, donor);
        context.startSequence().thenExecuteAfter(1, () -> {
            context.assertTrue(!first.isDone() && !second.isDone(), "落盘确认前就返回成功");
            context.assertTrue(opened(studentBody(player)) == openedBefore, "出资确认前已开脉");
            donor.gate().complete(null);
        }).thenWaitUntil(() -> context.assertTrue(first.isDone() && second.isDone(), "等待双方持久回执"))
                .thenExecute(() -> {
                    try {
                        context.assertTrue(!first.isCompletedExceptionally() && !second.isCompletedExceptionally(),
                                "异步受功失败");
                        var paid = first.join();
                        context.assertTrue(paid.isPresent() && paid.equals(second.join()), "并发点击回执不一致");
                        context.assertTrue(opened(studentBody(player)) == openedBefore + 1, "并发点击重复开脉");
                    } finally {
                        student.close();
                        elder.discard();
                    }
                }).thenSucceed();
    }

    private record DelayedDonor(HumanoidDonations source, CompletableFuture<Void> gate) implements InheritanceDonor {
        public Optional<BodySnapshot> inspect() {
            return source.inspect();
        }

        public Optional<LumenExport> lookup(UUID operation) {
            return source.lookup(operation);
        }

        public CompletableFuture<LumenExport> record(LumenExport allocation) {
            return source.record(allocation).thenCombine(gate, (record, ignored) -> record);
        }
    }

    static <T> T await(GameTestHelper context, CompletableFuture<T> result) {
        // 夹具在同一个世界 tick 内比较前后余额，允许主线程处理真正的异步写盘回调。
        context.getLevel().getServer().managedBlock(result::isDone);
        return result.join();
    }

    static Mob elder(GameTestHelper context) {
        // 指定档位、真实访客标签和实有内腔余额，出资不受本次出生抽签影响。
        var location = Vec3.atBottomCenterOf(context.absolutePos(new BlockPos(1, 1, 1)));
        var selection = new HumanoidSpawns.Selection(HumanoidSpecies.VILLAGER, HumanoidTier.TRUE_QI);
        var elder = HumanoidSpawns.spawn(context.getLevel(), selection, location, EntitySpawnReason.EVENT);
        elder.addTag(HumanoidVisitors.VISITOR);
        elder.setPersistenceRequired();
        elder.setNoAi(true /* noAi */);
        elder.setNoGravity(true /* noGravity */);
        var state = HumanoidTechniqueHost.prepare(elder).body();
        state.openOne("human:hand_meridian", HumanoidFabric.config().openedLumenGain());
        var source = state.currentBody().meridianStates().stream()
                .filter(item -> item.opening().stage() == MeridianOpening.Stage.OPENED)
                .findFirst().orElseThrow();
        var funds = DebugQi.newBuilder()
                .setMode(DebugQiMode.FILL_LUMENS)
                .setMeridianId(source.id().value())
                .setDensity(QiDensity.FREE)
                .setAmount(DONOR_AMOUNT)
                .build();
        var result = HumanoidHealthHost.debugQi(elder, funds);
        context.assertTrue(result == FailureCode.FAILURE_CODE_UNSPECIFIED, "老人资金夹具被拒 " + result);
        return elder;
    }

    static BodySnapshot studentBody(ServerPlayer player) {
        // 经正式身体宿主读取这名在线学生。
        return BodyProtobuf.fromMessage(VanillaDamageFabric.inspectBody(player).orElseThrow());
    }

    static long opened(BodySnapshot body) {
        // 读取真实开脉状态，以便发现重放另开了一条脉。
        return body.meridianStates().stream()
                .filter(state -> state.opening().stage() == MeridianOpening.Stage.OPENED).count();
    }
}
