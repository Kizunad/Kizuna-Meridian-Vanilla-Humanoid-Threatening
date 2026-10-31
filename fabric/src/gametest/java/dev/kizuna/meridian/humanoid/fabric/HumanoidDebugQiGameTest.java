package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidVariant;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import net.minecraft.core.BlockPos;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import java.util.Map;
import java.util.stream.Collectors;

import static dev.kizuna.meridian.humanoid.fabric.HumanoidGameTestTicks.DEFAULT_TIMEOUT;

/** 调试入口：真实生成的真炁卫道士补满与覆盖真元都不越过内腔上限。 */
public final class HumanoidDebugQiGameTest {
    private static final int POSITION = 2;
    private static final int SAMPLES = 8;
    private static final double SET_SHARE = 0.5;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void debugQiFillsAndSetsTrueQiVindicatorsWithinLumens(GameTestHelper context) {
        // 多个新生成的真炁卫道士覆盖生成器的残余与高密度主元组合；连补两次和全身覆盖都必须成功。
        var world = context.getLevel();
        Vec3 position = Vec3.atBottomCenterOf(context.absolutePos(new BlockPos(POSITION, POSITION, POSITION)));
        var selection = new HumanoidSpawns.Selection(HumanoidSpecies.VINDICATOR, HumanoidVariant.TRUE_QI);
        for (int index = 0; index < SAMPLES; index++) {
            Mob mob = HumanoidSpawns.spawn(world, selection, position, EntitySpawnReason.COMMAND);
            try {
                assertAccepted(context, HumanoidHealthHost.debugQi(mob, fill()), "补满被拒");
                assertAccepted(context, HumanoidHealthHost.debugQi(mob, fill()), "已满后再补被拒");
                assertWithinLumens(context, mob);
                assertAccepted(context, HumanoidHealthHost.debugQi(mob, set(mob)), "全身覆盖被拒");
                assertWithinLumens(context, mob);
            } finally {
                mob.discard();
            }
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void debugQiRejectsUnclaimedEntityAsBodyNotFound(GameTestHelper context) {
        // 非类人实体不由本宿主认领，不能因调试入口凭空创建身体。
        var cow = context.spawn(EntityTypes.COW, new BlockPos(POSITION, POSITION, POSITION));
        try {
            context.assertTrue(HumanoidHealthHost.debugQi(cow, fill()) == FailureCode.BODY_NOT_FOUND,
                    "非类人实体没有按无身体拒绝");
        } finally {
            cow.discard();
        }
        context.succeed();
    }

    private static DebugQi fill() {
        // 省略经脉与数量即补满全部内腔。
        return DebugQi.newBuilder()
                .setMode(DebugQiMode.FILL_LUMENS)
                .build();
    }

    private static DebugQi set(Mob mob) {
        // 全身覆盖为游离态，总量取内腔总容量的一半，避开在途预约带来的边界差异。
        BodySnapshot body = body(mob);
        double totalVolume = body.structure().meridians().stream()
                .mapToDouble(Anatomy.MeridianDefinition::lumenVolume)
                .sum();
        return DebugQi.newBuilder()
                .setMode(DebugQiMode.SET_INVENTORY)
                .setAmount(totalVolume * QiDensity.FREE * SET_SHARE)
                .setDensity(QiDensity.FREE)
                .build();
    }

    private static void assertAccepted(GameTestHelper context, FailureCode failure, String message) {
        // 失败码随消息输出，便于区分容量拒绝与内部错误。
        context.assertTrue(failure == FailureCode.FAILURE_CODE_UNSPECIFIED, message + "：" + failure);
    }

    private static void assertWithinLumens(GameTestHelper context, Mob mob) {
        // 逐条经脉比对占用体积与定义上限，不依赖快照构造时的舍入容差。
        BodySnapshot body = body(mob);
        Map<Anatomy.MeridianId, Double> limits = body.structure().meridians().stream()
                .collect(Collectors.toMap(Anatomy.MeridianDefinition::id, Anatomy.MeridianDefinition::lumenVolume));
        for (var state : body.meridianStates()) {
            context.assertTrue(state.lumenQi().volume() <= limits.get(state.id()),
                    "内腔占用越过上限：" + state.id().value());
        }
    }

    private static BodySnapshot body(Mob mob) {
        // 读取宿主当前身体，转换为 Framework 领域对象以复用体积计算。
        var snapshot = ((HumanoidEntityAccess) mob).meridian$humanoidBody().snapshot();
        return BodyProtobuf.fromMessage(snapshot);
    }
}
