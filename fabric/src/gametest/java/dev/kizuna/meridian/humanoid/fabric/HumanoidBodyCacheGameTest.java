package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidVariant;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.server.TechniqueState;
import java.util.OptionalLong;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import static dev.kizuna.meridian.humanoid.fabric.HumanoidGameTestTicks.DEFAULT_TIMEOUT;

/** 类人身体包装上的缓存和轻量时钟：身体每提交一次，读到的都必须是新的那一份。 */
public final class HumanoidBodyCacheGameTest {
    private static final int POSITION = 2;
    private static final int WAIT_TICKS = 5;
    private static final double SPARE_LIFESPAN_SECONDS = 1;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void snapshotFollowsEveryCommit(GameTestHelper context) {
        // 身体没变时协议消息复用同一份；补满真元提交新版本后，读到的必须是新身体。
        Mob mob = spawn(context);
        try {
            HumanoidBodyState body = ((HumanoidEntityAccess) mob).meridian$humanoidBody();
            var before = body.snapshot();
            context.assertTrue(before == body.snapshot(), "身体没变时协议消息没有复用");
            DebugQi fill = DebugQi.newBuilder().setMode(DebugQiMode.FILL_LUMENS).build();
            context.assertTrue(HumanoidHealthHost.debugQi(mob, fill) == FailureCode.FAILURE_CODE_UNSPECIFIED,
                    "补满真元被拒");
            var current = body.currentBody();
            context.assertTrue(current.revision() > before.getRevision(), "补满真元没有提交新版本");
            context.assertTrue(body.snapshot().equals(BodyProtobuf.toMessage(current)),
                    "协议消息没有跟上已提交的身体");
        } finally {
            mob.discard();
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void techniqueProgressFollowsEveryCommit(GameTestHelper context) {
        // 新学一式会提交新身体；缓存的功法进度必须跟着换，否则大脑会按旧进度判断在不在施放。
        Mob mob = spawn(context);
        try {
            HumanoidBodyState body = ((HumanoidEntityAccess) mob).meridian$humanoidBody();
            TechniqueProgress before = body.techniqueProgress();
            context.assertTrue(before == body.techniqueProgress(), "身体没变时功法进度没有复用");
            TechniqueDefinition unknown = unknownTechnique(before);
            TechniqueFabric.service().learn(mob.getUUID(), unknown);
            TechniqueProgress committed = TechniqueState.read(body.currentBody());
            context.assertTrue(committed.getKnownCount() == before.getKnownCount() + 1, "新学的式没有写进身体");
            context.assertTrue(body.techniqueProgress().equals(committed), "功法进度没有跟上已提交的身体");
        } finally {
            mob.discard();
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void lightClockMatchesSettledBody(GameTestHelper context) {
        // 过几个 tick 以后，轻量时钟报的数要等于随后真正结清得到的身体时钟，而且确实往前走了。
        Mob mob = spawn(context);
        HumanoidBodyState body = ((HumanoidEntityAccess) mob).meridian$humanoidBody();
        long born = body.currentBody().settledAt().elapsedMillis();
        context.runAfterDelay(WAIT_TICKS, () -> {
            try {
                var committed = body.currentBody();
                OptionalLong clock = HumanoidTechniqueHost.clockMillis(mob);
                context.assertTrue(clock.isPresent(), "已绑定的活体没有报出时钟");
                context.assertTrue(body.currentBody() == committed, "读时钟不应结清身体");
                long settled = HumanoidTechniqueHost.prepare(mob).body().currentBody().settledAt().elapsedMillis();
                context.assertTrue(settled > born, "等待期间身体时钟没有前进");
                context.assertTrue(clock.getAsLong() == settled,
                        "轻量时钟 " + clock.getAsLong() + " 与结清后的身体时钟 " + settled + " 不一致");
            } finally {
                mob.discard();
            }
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void lightClockIsWithheldOnceLifespanIsUsedUp(GameTestHelper context) {
        // 寿元已经用完、身体还没被结清判死：真正结清会停在寿元终点并判死，所以轻量时钟必须答不出，
        // 让调用方去走会结清的路径。同样换一具身体、寿元还剩一秒时照常报数。
        Mob mob = spawn(context);
        try {
            HumanoidBodyState spare = rebindWithLifespanLeft(mob, SPARE_LIFESPAN_SECONDS);
            context.assertTrue(!spare.currentBody().vitality().dead(), "寿元未尽的身体不该是死的");
            context.assertTrue(HumanoidTechniqueHost.clockMillis(mob).isPresent(), "寿元未尽的活体没有报出时钟");

            HumanoidBodyState exhausted = rebindWithLifespanLeft(mob, 0);
            context.assertTrue(!exhausted.currentBody().vitality().dead(), "同一毫秒内不该已经结清判死");
            context.assertTrue(HumanoidTechniqueHost.clockMillis(mob).isEmpty(), "寿元用完后仍报出轻量时钟");
        } finally {
            mob.discard();
        }
        context.succeed();
    }

    private static HumanoidBodyState rebindWithLifespanLeft(Mob mob, double secondsLeft) {
        // 把寿命上限压到只剩这么多秒，换成一具新读档的身体并重新发布会话；年龄和已耗寿元不动。
        var access = (HumanoidEntityAccess) mob;
        var living = access.meridian$humanoidBody().snapshot();
        var vitality = living.getVitality();
        double limit = vitality.getAgeSeconds() + vitality.getSpentLifespanSeconds() + secondsLeft;
        var shortened = living.toBuilder()
                .setVitality(vitality.toBuilder().setLifespanLimitSeconds(limit))
                .build();
        HumanoidTechniqueHost.removed(mob);
        var saved = new HumanoidBodyState.SavedBody(mob.getUUID(), shortened.toByteArray(), new byte[0][],
                HumanoidFabric.region(mob).sourceId());
        HumanoidBodyState body = HumanoidBodyState.restore(mob.getUUID(), saved, HumanoidFabric.now(),
                HumanoidSpecies.VINDICATOR);
        access.meridian$humanoidBody(body);
        HumanoidTechniqueHost.prepare(mob);
        return body;
    }

    private static Mob spawn(GameTestHelper context) {
        // 真实生成的真炁卫道士：出生即绑定身体、发布会话并学完默认卷。
        Vec3 position = Vec3.atBottomCenterOf(context.absolutePos(new BlockPos(POSITION, POSITION, POSITION)));
        var selection = new HumanoidSpawns.Selection(HumanoidSpecies.VINDICATOR, HumanoidVariant.TRUE_QI);
        Mob mob = HumanoidSpawns.spawn(context.getLevel(), selection, position, EntitySpawnReason.COMMAND);
        mob.setNoAi(true /* noAi */);
        return mob;
    }

    private static TechniqueDefinition unknownTechnique(TechniqueProgress progress) {
        // 目录里任取一式还没学过的；天生会的式学了也不留记录，不能拿来验「新学一式」。
        return TechniqueFabric.service().registry().definitions().stream()
                .filter(definition -> !definition.getInnate())
                .filter(definition -> progress.getKnownList().stream()
                        .noneMatch(known -> known.getTechnique().getId().equals(definition.getId())))
                .findFirst().orElseThrow();
    }
}
