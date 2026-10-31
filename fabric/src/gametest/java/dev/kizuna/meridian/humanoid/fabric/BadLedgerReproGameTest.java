package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.protocol.pb.MeridianMessages;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import java.nio.file.Files;
import java.util.Arrays;

import static dev.kizuna.meridian.humanoid.fabric.HumanoidGameTestTicks.DEFAULT_TIMEOUT;

public final class BadLedgerReproGameTest {
    private static final int SPAWN_COORDINATE = 2;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void audit04CorruptLedgerMustNotBecomeUnbound(GameTestHelper context) throws Exception {
        // 服务器已经从坏账本启动，再恢复上一进程保存的真实村民身体。
        // 确认真实 Human 接收端已初始化，否则抛出 IllegalStateException。
        AuditReproSupport.human();
        context.assertTrue(!HumanoidFabric.isReady(), "坏档没有使释放账本恢复失败");
        CompoundTag saved = AuditReproSupport.read(AuditReproSupport.evidence(AuditReproSupport.FIXTURE_ENTITY_FILE));
        var entity = AuditReproSupport.restoredVillager(context, saved);
        var access = (HumanoidEntityAccess) entity;
        context.assertTrue(access.meridian$humanoidSavedBody().length > 0, "实体没有带入已保存身体");
        float before = entity.getHealth();
        String damageResult = assertDamageRejected(context, entity);
        float after = entity.getHealth();
        var response = AuditReproSupport.inspect(entity);
        String evidence = "REPRO-A-04 expected=FAILURE actual=" + response.getResultCase()
                + " rawHpBefore=" + before + " rawHpAfter=" + after + " damage=" + damageResult;
        System.out.println(evidence);
        context.assertTrue(response.hasFailure()
                && response.getFailure().getCode() == MeridianMessages.FailureCode.INTERNAL_ERROR, evidence);
        context.assertTrue(before == after, "坏账本使已绑定实体退回了原版 HP");
        var cow = context.spawn(EntityTypes.COW, SPAWN_COORDINATE, SPAWN_COORDINATE, SPAWN_COORDINATE);
        context.assertTrue(AuditReproSupport.inspect(cow).getUnbound(), "未认领实体被坏账本错误接管");
        verifyLedgerNotOverwritten(context);
        context.succeed();
    }

    private static String assertDamageRejected(GameTestHelper context, LivingEntity entity) {
        // 必需宿主拒绝请求时 Damage 会抛错；只接受对应失败码，不能吞掉其他运行错误。
        // 26.2 的 hurt 不再返回结果，改用服务端入口 hurtServer 取是否受伤。
        try {
            boolean damaged = entity.hurtServer(context.getLevel(), entity.damageSources().generic(), 1);
            context.assertTrue(!damaged, "坏账本仍允许原版伤害成功");
            return "returned:false";
        } catch (IllegalStateException rejected) {
            String message = rejected.getMessage();
            String expectedCode = MeridianMessages.FailureCode.INTERNAL_ERROR.name();
            context.assertTrue(message != null && message.endsWith(expectedCode), "伤害因其他错误失败：" + rejected);
            return "threw:" + expectedCode;
        }
    }

    private static void verifyLedgerNotOverwritten(GameTestHelper context) throws Exception {
        // 账本加载失败后，下一次世界保存也不能把坏文件替换成一个新空账本；26.2 起账本跟随世界保存自写盘。
        var file = AuditReproSupport.worldFile(context, AuditReproSupport.SENDER_FILE);
        byte[] original = Files.readAllBytes(file);
        context.getLevel().getServer().saveAllChunks(false /* silent */, true /* flush */, false /* force */);
        context.assertTrue(Arrays.equals(original, Files.readAllBytes(file)), "坏释放账本被下一次保存覆盖");
    }
}
