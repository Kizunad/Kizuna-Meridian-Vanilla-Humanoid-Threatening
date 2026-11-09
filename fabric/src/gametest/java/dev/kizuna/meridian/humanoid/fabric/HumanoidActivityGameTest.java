package dev.kizuna.meridian.humanoid.fabric;

import com.google.protobuf.Any;
import com.google.protobuf.StringValue;
import dev.kizuna.meridian.protocol.ActivityHostProtobuf;
import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.ActivityHostMessages.ActivityHostRequest;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.ActivityEffort;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostRequest;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.server.BodyActivityHost;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;

/** 类人活动宿主的身份、版本、重放和操作范围契约。 */
public final class HumanoidActivityGameTest {
    private static final String CALLER = "meridian_test:activity";
    private static final int SPAWN_COORDINATE = 2;

    @GameTest
    public void activityCommitsOnceAndRejectsForeignBodyAndOtherOperations(GameTestHelper context) {
        // 使用登记的活动宿主与同一健康身体，成功重放不重复修改权威版本。
        var entity = context.spawn(EntityTypes.VILLAGER,
                new BlockPos(SPAWN_COORDINATE, SPAWN_COORDINATE, SPAWN_COORDINATE));
        entity.setNoAi(true /* noAi */);
        BodySnapshot before = inspect(entity);
        BodyActivity plan = BodyActivity.newBuilder()
                .putProgress(CALLER, Any.pack(StringValue.of("committed")))
                .build();
        ActivityHostRequest request = request(entity, before, plan, "activity-once");
        HealthHostResponse first = exchange(request);
        context.assertTrue(first.hasBody(), "活动未被类人宿主认领：" + first);
        BodySnapshot committed = first.getBody();
        context.assertTrue(committed.getRevision() == before.getRevision() + 1, "活动没有提交一次");
        context.assertTrue(committed.containsAddonProgress(CALLER), "进度未写入同一身体");
        context.assertTrue(exchange(request).getBody().equals(committed), "活动重放改变身体");
        assertRejectedOperations(context, entity, committed);
        entity.discard();
        context.succeed();
    }

    private static void assertRejectedOperations(GameTestHelper context, LivingEntity entity, BodySnapshot body) {
        // 不能通过该入口消耗资源或借用另一具身体 ID；拒绝不改变已提交状态。
        ActivityEffort effort = ActivityEffort.newBuilder()
                .setTissueId(body.getStructure().getTissues(0).getId())
                .setWatts(1)
                .setSeconds(1)
                .build();
        BodyActivity forbidden = BodyActivity.newBuilder()
                .addEfforts(effort)
                .build();
        var denied = exchange(request(entity, body, forbidden, "activity-effort"));
        context.assertTrue(denied.getFailure().getCode() == FailureCode.ACCESS_DENIED, "活动越权做功未拒绝");
        BodySnapshot foreign = body.toBuilder()
                .setId("meridian_test:foreign")
                .build();
        var wrongBody = exchange(request(entity, foreign, BodyActivity.getDefaultInstance(), "activity-foreign"));
        context.assertTrue(wrongBody.getFailure().getCode() == FailureCode.ACCESS_DENIED, "跨身体活动未拒绝");
        BodySnapshot stale = body.toBuilder()
                .setRevision(body.getRevision() - 1)
                .build();
        var conflict = exchange(request(entity, stale, BodyActivity.getDefaultInstance(), "activity-stale"));
        context.assertTrue(conflict.hasFailure(), "宿主刷新版本掩盖过期请求");
        context.assertTrue(inspect(entity).equals(body), "拒绝活动改变了身体");
    }

    private static ActivityHostRequest request(LivingEntity entity, BodySnapshot body, BodyActivity plan, String id) {
        // 内外 request ID 一致，预期版本来自调用方读取的快照。
        BodyCommand command = BodyCommand.newBuilder()
                .setBodyId(body.getId())
                .setExpectedRevision(body.getRevision())
                .setExecuteActivity(plan)
                .build();
        RequestEnvelope envelope = RequestEnvelope.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(id)
                .setBodyCommand(command)
                .build();
        return ActivityHostRequest.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(id)
                .setSubjectId(entity.getStringUUID())
                .setActivity(envelope)
                .build();
    }

    private static HealthHostResponse exchange(ActivityHostRequest request) {
        // 必须通过正式 entrypoint 找到类人宿主，漏登记也会使回归失败。
        BodyActivityHost host = FabricLoader.getInstance()
                .getEntrypoints("meridian_activity_host", BodyActivityHost.class)
                .stream().filter(candidate -> candidate instanceof HumanoidActivityHost).findFirst().orElseThrow();
        return HealthHostProtobuf.decodeResponse(host.exchange(CALLER, ActivityHostProtobuf.encodeRequest(request)));
    }

    private static BodySnapshot inspect(LivingEntity entity) {
        // 健康查询建立实际类人身体；测试没有另建 Store 或身体模板。
        var request = HealthHostRequest.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId("activity-inspect")
                .setSubjectId(entity.getStringUUID())
                .setInspect(true /* inspect */)
                .build();
        byte[] result = new HumanoidHealthHost().exchange(HealthHostProtobuf.encodeRequest(request));
        return HealthHostProtobuf.decodeResponse(result).getBody();
    }
}
