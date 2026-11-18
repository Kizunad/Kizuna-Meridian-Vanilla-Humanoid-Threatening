package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestFailure;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.AiRole;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveCast;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.MovementAction;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationAction;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueActivityCommit;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostResponse;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.server.TechniqueBodyStamp;
import dev.kizuna.meridian.technique.server.TechniqueErrors;
import dev.kizuna.meridian.technique.server.TechniqueForecasts;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import dev.kizuna.meridian.technique.server.TechniqueHostEntrypoint;
import dev.kizuna.meridian.technique.server.TechniqueProtocol;
import dev.kizuna.meridian.technique.server.TechniqueRecoveryHost;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
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
            if (body.currentBody().vitality().dead()) {
                recover(entity, session);
            }
            response.setBody(body.snapshot());
            response.setHeldItems(HumanoidWayfarerWeapons.inspect(entity));
            response.setInspectFacts(HumanoidTechniqueFacts.inspect(entity));
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

    @Override
    public Optional<TechniqueBodyStamp> bodyStamp(UUID entityUuid) {
        // 只在 inspect 会原样成功的状态下报读数；其余情况为空，功法服务退回 inspect。
        var server = HumanoidFabric.server();
        if (server == null || !server.isSameThread() || !HumanoidFabric.isReady()) {
            return Optional.empty();
        }
        LivingEntity entity = HumanoidFabric.findEntity(entityUuid);
        if (entity == null || !HumanoidFabric.isClaimed(entity)) {
            return Optional.empty();
        }
        OptionalLong clock = clockMillis(entity);
        if (clock.isEmpty()) {
            return Optional.empty();
        }
        long revision = sessions.get(entityUuid).body().currentBody().revision();
        return Optional.of(new TechniqueBodyStamp(revision, clock.getAsLong()));
    }

    static OptionalLong clockMillis(LivingEntity entity) {
        // 身体要已绑定、会话已发布、学卷已发完：这些都由 prepare 完成，缺任何一项都得走 prepare。
        var access = (HumanoidEntityAccess) entity;
        Session session = sessions.get(entity.getUUID());
        if (session == null || !session.volumesAssigned || access.meridian$humanoidLoadFailed()
                || access.meridian$humanoidLoading() || access.meridian$humanoidBody() != session.body()) {
            return OptionalLong.empty();
        }
        try {
            return session.body().projectedClockMillis(HumanoidFabric.now());
        } catch (HumanoidEnvironmentClient.Unavailable unavailable) {
            return OptionalLong.empty();
        }
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
                || session.body().currentBody().vitality().dead()) {
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
        // 档位与生成命令一样从身体快照读回：凡人是游炁境界下一条经脉都没开，查的是凡人档而不是游炁档。
        var service = TechniqueFabric.service();
        String entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        HumanoidTier tier = HumanoidTier.of(body.snapshot());
        for (String volume : HumanoidBrain.config().volumes().volumes(entityId, tier, entity.getUUID())) {
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

    static boolean castingWayfarer(LivingEntity entity) {
        // 行矢施放时要继续检查承接窗口，出手段结束前暂停导航；不改变其他功法的移动策略。
        return publishedProgress(entity).filter(TechniqueProgress::hasActiveCast)
                .map(progress -> progress.getActiveCast().getRequest().getTechnique().getId())
                .filter(id -> id.startsWith(HumanoidWayfarerAi.PREFIX))
                .isPresent();
    }

    static boolean castingCloseStrike(LivingEntity entity) {
        // 近战打击的首击依赖施放前的距离，起手到出手段结束停止导航但继续面向当前目标。
        return activeDefinition(entity).map(HumanoidTechniqueHost::hasCloseStrike).orElse(false);
    }

    static boolean beforeRecovery(LivingEntity entity) {
        // 当前施放还没过出手段；各式的打击与放箭都在出手段结束前落下，之后只剩收式。
        // 不在施放为假；读不到身体时钟时按还在出手段处理。
        Optional<Long> activeEnd = publishedProgress(entity).filter(TechniqueProgress::hasActiveCast)
                .map(progress -> progress.getActiveCast().getActiveEndMillis());
        if (activeEnd.isEmpty()) {
            return false;
        }
        OptionalLong clock = clockMillis(entity);
        return clock.isEmpty() || clock.getAsLong() <= activeEnd.get();
    }

    static boolean beforeWarmupEnd(LivingEntity entity) {
        var warmupEnd = activeCast(entity).map(ActiveCast::getWarmupEndMillis);
        var clock = clockMillis(entity);
        return warmupEnd.isPresent() && clock.isPresent() && clock.getAsLong() < warmupEnd.get();
    }

    static Optional<ActiveCast> activeCast(LivingEntity entity) {
        return publishedProgress(entity).filter(TechniqueProgress::hasActiveCast)
                .map(TechniqueProgress::getActiveCast);
    }

    private static Optional<TechniqueDefinition> activeDefinition(LivingEntity entity) {
        // 只从已发布进度读取当前式，不结清身体时间或创建新会话。
        return publishedProgress(entity).filter(TechniqueProgress::hasActiveCast)
                .flatMap(progress -> TechniqueFabric.service().registry()
                        .find(progress.getActiveCast().getRequest().getTechnique()));
    }

    private static boolean hasCloseStrike(TechniqueDefinition definition) {
        // 兵器、腿、拳掌、擒的第一下都靠起手时的站位够着；行矢另由 castingWayfarer 处理。
        return TechniqueForecasts.firstHit(definition).filter(TechniqueForecasts.FirstHit::close).isPresent();
    }

    private static Optional<TechniqueProgress> publishedProgress(LivingEntity entity) {
        // 已发布会话快照里的功法进度；没有会话时为空。
        // 直接读已提交的快照，进度按快照缓存，不为了看「在不在施放」转一遍协议。
        Session session = sessions.get(entity.getUUID());
        if (session == null) {
            return Optional.empty();
        }
        return Optional.of(session.body().techniqueProgress());
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
        if (session != null && session.body().currentBody().vitality().dead()) {
            recover(entity, session);
        }
    }

    static void removed(LivingEntity entity) {
        // 卸载前清理当前对象；已写出的旧 NBT 也会在下次绑定时恢复清理。
        Session session = sessions.get(entity.getUUID());
        HumanoidBrain.forget(entity.getUUID());
        if (session != null) {
            try {
                // 停服开始时功法服务已经逐个取消施放、写回身体并关闭，之后世界才存盘、实体才卸载：
                // 这时再找它恢复只会抛「功法服务不可用」，每只生物一条报错。只放掉会话；身体随实体存档，万一有残留下次绑定时照旧清。
                if (HumanoidFabric.isReady() && !HumanoidFabric.isStopping()) {
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
                // 直接交出当前权威快照，不建立平行状态。
                return session.body().currentBody();
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
