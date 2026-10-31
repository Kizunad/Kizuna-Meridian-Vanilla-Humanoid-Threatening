package dev.kizuna.meridian.humanoid.fabric;

import com.google.protobuf.Any;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestFailure;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueWorldItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.AiRole;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.MovementAction;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationAction;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueActivityCommit;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostResponse;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.server.TechniqueErrors;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import dev.kizuna.meridian.technique.server.TechniqueHostEntrypoint;
import dev.kizuna.meridian.technique.server.TechniqueProtocol;
import dev.kizuna.meridian.technique.server.TechniqueRecoveryHost;
import dev.kizuna.meridian.technique.server.TechniqueState;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;

/** 类人生物的可信功法入口，活动、知识和冷却均随原身体保存。 */
public final class HumanoidTechniqueHost implements TechniqueHostEntrypoint {
    private static final Map<UUID, Session> sessions = new HashMap<>();

    @Override
    public byte[] exchange(byte[] payload) {
        // 只有主线程已加载、已认领且成功读档的实体能够提交功法。
        TechniqueHostRequest request = TechniqueProtocol.decodeHost(payload);
        var response = TechniqueHostResponse.newBuilder().setRequestId(request.getRequestId());
        var server = HumanoidFabric.server();
        if (server == null) {
            return encode(response.setUnbound(true /* unbound */));
        }
        if (!server.isSameThread()) {
            return failure(response, FailureCode.ACCESS_DENIED);
        }
        LivingEntity entity = HumanoidFabric.findEntity(UUID.fromString(request.getEntityUuid()));
        if (entity == null || !HumanoidFabric.isClaimed(entity)) {
            return encode(response.setUnbound(true /* unbound */));
        }
        var access = (HumanoidEntityAccess) entity;
        if (!HumanoidFabric.isReady() || access.meridian$humanoidLoadFailed()) {
            return failure(response, FailureCode.INTERNAL_ERROR);
        }
        if (access.meridian$humanoidLoading()) {
            return encode(response.setUnbound(true /* unbound */));
        }
        try {
            return execute(entity, request, response);
        } catch (TechniqueErrors.HostRejected rejected) {
            return failure(response, rejected.failure());
        } catch (HumanoidBodyState.HumanoidHealthFailure rejected) {
            return failure(response, rejected.code());
        } catch (HumanoidEnvironmentClient.Unavailable unavailable) {
            return failure(response, unavailable.code());
        } catch (RuntimeException rejected) {
            HumanoidFabric.report(entity, rejected);
            return failure(response, FailureCode.INTERNAL_ERROR);
        }
    }

    private static byte[] execute(LivingEntity entity, TechniqueHostRequest request,
                                  TechniqueHostResponse.Builder response) {
        // 每次查询先结清同一身体时间，恢复不补发离线伤害或冲量。
        Session session = prepare(entity);
        HumanoidBodyState body = session.body();
        if (request.hasInspect()) {
            if (body.snapshot().getVitality().getDead()) {
                recover(entity, session);
            }
            response.setBody(body.snapshot());
            response.setHeldItems(TechniqueWorldItems.inspect(entity));
        } else if (request.hasActivity()) {
            var result = body.applyTechnique(request);
            if (result.hasFailure()) {
                return failure(response, result.getFailure().getCode());
            }
            TechniqueActivityCommit commit = TechniqueActivityCommit.newBuilder()
                    .setRevision(result.getCommandCommitted().getRevision())
                    .setActivity(request.getActivity())
                    .build();
            response.setActivity(commit);
        } else {
            var submission = new TechniqueHost.EffectSubmission(request.getRequestId(), request.getEffect());
            session.effects().submit(submission);
            response.setEffectAccepted(true /* effectAccepted */);
        }
        return encode(response);
    }

    static Session prepare(LivingEntity entity) {
        // 首次绑定先恢复再发布会话，学卷递归查询只读取已发布的同一会话。
        var now = HumanoidFabric.now();
        HumanoidSpecies species = HumanoidSpecies.fromEntityId(BuiltInRegistries.ENTITY_TYPE
                .getKey(entity.getType()).toString()).orElseThrow();
        HumanoidBodyState body = HumanoidHealthHost.bodyState(entity, species, now);
        HumanoidFabric.enqueueRelease(entity, body.settle(now));
        Session existing = sessions.get(entity.getUUID());
        if (existing != null && existing.body() == body) {
            assignVolumes(entity, existing);
            return existing;
        }
        var created = new Session(body, new HumanoidTechniqueEffects(entity));
        recover(entity, created);
        sessions.put(entity.getUUID(), created);
        assignVolumes(entity, created);
        return created;
    }

    private static void assignVolumes(LivingEntity entity, Session session) {
        // 学卷触发宿主递归查询时复用会话；失败保留下一次绑定的重试机会。
        if (session.volumesAssigned || session.assigning || HumanoidBrain.config() == null
                || session.body().snapshot().getVitality().getDead()) {
            return;
        }
        session.assigning = true;
        try {
            learnVolumes(entity, session.body());
            session.volumesAssigned = true;
        } finally {
            session.assigning = false;
        }
    }

    private static void learnVolumes(LivingEntity entity, HumanoidBodyState body) {
        // 按大脑配置的学卷表分配，概率按 UUID 分桶，不改变身体生成器原有随机调用序列；已学的卷再学不变。
        var service = TechniqueFabric.service();
        String entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        Realm realm = Realm.valueOf(body.snapshot().getRealm().name());
        for (String volume : HumanoidBrain.config().volumes().volumes(entityId, realm, entity.getUUID())) {
            service.learn(entity.getUUID(), volume);
        }
    }

    static boolean knowsRole(LivingEntity entity, AiRole role) {
        // 只读已发布会话的快照，不结清时间也不生成身体；没有会话或未学功法的实体视为不会。
        var registry = TechniqueFabric.service().registry();
        return publishedProgress(entity).stream()
                .flatMap(progress -> progress.getKnownList().stream())
                .flatMap(known -> registry.find(known.getTechnique()).stream())
                .anyMatch(definition -> definition.getAiHints().getSupport().getRole() == role);
    }

    static boolean hasActiveCast(LivingEntity entity) {
        // 有当前施放即真，不论这一式是否限速；同样只读已发布会话，没有会话视为不在施放。
        return publishedProgress(entity).filter(TechniqueProgress::hasActiveCast).isPresent();
    }

    private static Optional<TechniqueProgress> publishedProgress(LivingEntity entity) {
        // 已发布会话快照里的功法进度；没有会话或没有进度时为空。
        Session session = sessions.get(entity.getUUID());
        if (session == null) {
            return Optional.empty();
        }
        Any payload = session.body().snapshot().getAddonProgressMap().get(TechniqueState.KEY);
        if (payload == null || !payload.is(TechniqueProgress.class)) {
            return Optional.empty();
        }
        return Optional.of(TechniqueState.decode(payload.getValue().toByteArray()));
    }

    static boolean hasSession(LivingEntity entity) {
        // 是否已为该实体准备过身体会话，GameTest 与排查用。
        return sessions.containsKey(entity.getUUID());
    }

    static boolean isCasting(LivingEntity entity) {
        // 限速施放（有移动倍率的式从起手到收势）：原版近战互斥与举武器姿态用，不触发身体生成。
        Session session = sessions.get(entity.getUUID());
        return session != null && session.effects().isCasting();
    }

    static void damaged(LivingEntity entity) {
        // 健康结算后只处理死亡，用可信恢复入口结束残留活动；受击打断由伤害附属按僵直统一判定。
        Session session = sessions.get(entity.getUUID());
        if (session != null && session.body().snapshot().getVitality().getDead()) {
            recover(entity, session);
        }
    }

    static void removed(LivingEntity entity) {
        // 卸载前清理当前对象；已写出的旧 NBT 也会在下次绑定时恢复清理。
        Session session = sessions.get(entity.getUUID());
        HumanoidBrain.forget(entity.getUUID());
        if (session != null) {
            try {
                if (HumanoidFabric.isReady()) {
                    recover(entity, session);
                }
            } finally {
                sessions.remove(entity.getUUID());
            }
        }
    }

    static void clear() {
        // 功法服务已在停服前取消全部施放，此处只释放实体引用。
        sessions.clear();
        HumanoidBrain.clear();
    }

    private static void recover(LivingEntity entity, Session session) {
        // 恢复直连原身体，避免通过 inspect 重入同一恢复流程。
        TechniqueFabric.service().recoverOnLoad(entity.getUUID(), new TechniqueRecoveryHost() {
            @Override
            public BodySnapshot snapshot() {
                // 转换当前权威快照，不建立平行状态。
                return BodyProtobuf.fromMessage(session.body().snapshot());
            }

            @Override
            public long submitRecovery(String requestId, BodyCommand command) {
                // 恢复命令仍由 Framework 校验版本和可信身份。
                return session.body().submitRecovery(requestId, command);
            }

            @Override
            public TechniqueHost.EffectCommit submitEffect(TechniqueHost.EffectSubmission submission) {
                // 恢复只允许停止和解锁，绝不重放离线世界效果。
                var effect = submission.effect();
                if (!(effect.hasMovement() && effect.getMovement().getAction() == MovementAction.UNLOCK)
                        && !(effect.hasPresentation()
                        && effect.getPresentation().getAction() == PresentationAction.STOP)) {
                    throw new TechniqueErrors.HostRejected(FailureCode.ACCESS_DENIED);
                }
                return session.effects().submit(submission);
            }
        });
    }

    private static byte[] failure(TechniqueHostResponse.Builder response, FailureCode code) {
        // 保留 Framework 原始业务失败码。
        var failure = RequestFailure.newBuilder().setCode(code).build();
        return encode(response.setFailure(failure));
    }

    private static byte[] encode(TechniqueHostResponse.Builder response) {
        // 所有响应经过共享协议的结构验证。
        return TechniqueProtocol.encodeHostResponse(response.build());
    }

    static final class Session {
        private final HumanoidBodyState body;
        private final HumanoidTechniqueEffects effects;
        private boolean volumesAssigned;
        private boolean assigning;

        private Session(HumanoidBodyState body, HumanoidTechniqueEffects effects) {
            // 只保存当前实体适配，知识和学卷提交记录始终读取身体。
            this.body = body;
            this.effects = effects;
        }

        HumanoidBodyState body() {
            // 身体引用直接指向实体唯一 Store 的封装。
            return body;
        }

        HumanoidTechniqueEffects effects() {
            // 效果身份与实体生命周期一致。
            return effects;
        }
    }
}
