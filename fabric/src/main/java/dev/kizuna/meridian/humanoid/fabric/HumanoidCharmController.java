package dev.kizuna.meridian.humanoid.fabric;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.brewing.fabric.BrewCharmTargets;
import dev.kizuna.meridian.brewing.protocol.pb.CharmMessages.CharmCommand;
import dev.kizuna.meridian.brewing.protocol.pb.CharmMessages.CharmEndReason;
import dev.kizuna.meridian.brewing.protocol.pb.CharmMessages.CharmReply;
import dev.kizuna.meridian.brewing.protocol.pb.CharmMessages.CharmSession;
import dev.kizuna.meridian.brewing.server.CharmController;
import dev.kizuna.meridian.human.protocol.pb.ControlMessages.ControlCommand;
import dev.kizuna.meridian.human.protocol.pb.ControlMessages.ControlReply;
import dev.kizuna.meridian.human.protocol.pb.ControlMessages.ControlSession;
import dev.kizuna.meridian.human.server.PlayerControlHost;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 执行 Brewing 的会话，不重新判定脑力、阵营归属或药效期限。
 *
 * <p>受控者不打施药者一方；生物施药者也不打自己魅惑着的生物：会话一开始就放下这个目标、两边互相的旧账一起清掉，
 * 会话期间选敌、原版改目标、出手筛选都不认它。醒了以后不凭旧账复打。</p>
 */
public final class HumanoidCharmController implements CharmController {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_humanoid");
    private static final int MAX_BYTES = 4_096;
    private static final double FOLLOW_DISTANCE = 2;
    private static final double FOLLOW_SPEED = 1;
    private static final Map<UUID, CharmSession> sessions = new HashMap<>();
    private static final Map<UUID, CharmSession> starts = new HashMap<>();
    private static final Map<String, Long> ended = new HashMap<>();
    private static PlayerControlHost players;

    static void initialize() {
        // 只有唯一玩家宿主能够接管真实连接，已发出的投射物也受阵营门禁限制。
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            var hosts = FabricLoader.getInstance().getEntrypoints(PlayerControlHost.ENTRYPOINT,
                    PlayerControlHost.class);
            if (hosts.size() != 1) {
                throw new IllegalStateException("魅惑控制缺少唯一玩家宿主");
            }
            players = hosts.getFirst();
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((target, source, amount) ->
                !(source.getEntity() instanceof LivingEntity attacker) || !protects(attacker, target));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            sessions.clear();
            starts.clear();
            ended.clear();
            players = null;
        });
    }

    @Override
    public byte[] exchange(String caller, byte[] payload) {
        // 只接受本进程的 Brewing 主线程调用；客户端不能直接创建控制身份。
        var server = HumanoidFabric.server();
        if (!CALLER.equals(caller) || server == null || !server.isSameThread()
                || payload.length == 0 || payload.length > MAX_BYTES) {
            throw new IllegalArgumentException("魅惑控制请求来源或大小无效");
        }
        try {
            CharmCommand command = CharmCommand.parseFrom(payload);
            validate(command);
            CharmSession session = command.getSession();
            String sessionId = session.getSessionId();
            UUID subject = UUID.fromString(session.getSubjectUuid());
            UUID.fromString(sessionId); // 校验稳定会话身份，非法时明确抛出异常。
            UUID.fromString(session.getCasterUuid()); // 施药者身份也必须是 UUID。
            boolean accepted = apply(command, subject);
            return CharmReply.newBuilder()
                    .setSessionId(sessionId)
                    .setAccepted(accepted)
                    .build().toByteArray();
        } catch (InvalidProtocolBufferException invalid) {
            throw new IllegalArgumentException("魅惑控制协议无效", invalid);
        }
    }

    private static boolean apply(CharmCommand command, UUID subjectId) {
        // 结束墓碑保留到原期限，结束后重试 START 也不能复活旧控制。
        CharmSession session = command.getSession();
        String sessionId = session.getSessionId();
        long endsTick = session.getEndsTick();
        var action = command.getAction();
        long tick = HumanoidFabric.server().overworld().getGameTime();
        ended.values().removeIf(expiry -> expiry < tick);
        CharmSession previous = sessions.get(subjectId);
        if (action == CharmCommand.Action.END) {
            long tombstoneUntil;
            if (previous != null && previous.getSessionId().equals(sessionId)) {
                tombstoneUntil = starts.get(subjectId).getEndsTick();
                // 一局魅惑一行日志，排查「怎么又打起来了」时看它是怎么醒的。
                LOGGER.info("魅惑会话结束：受控 {}，施药者 {}，原因 {}，第 {} tick（原定到 {}）", subjectId,
                        session.getCasterUuid(), command.getReason(), tick, tombstoneUntil);
                finish(command, subjectId);
            } else {
                tombstoneUntil = endsTick;
            }
            ended.merge(sessionId, tombstoneUntil, Math::max);
            return true;
        }
        if (ended.containsKey(sessionId) || tick >= endsTick) {
            return true;
        }
        if (action == CharmCommand.Action.START && previous != null) {
            return session.equals(starts.get(subjectId));
        }
        return update(command, subjectId, previous);
    }

    private static boolean update(CharmCommand command, UUID subjectId, CharmSession previous) {
        // 首次认领与后续缩时分别校验，重试 START 不回滚已经更新的目标或期限。
        CharmSession session = command.getSession();
        LivingEntity subject = HumanoidFabric.findEntity(subjectId);
        if (subject == null || !subject.isAlive()) {
            return false;
        }
        if (previous != null && !sameIdentity(previous, session)
                || previous == null && command.getAction() != CharmCommand.Action.START) {
            return false;
        }
        if (subject instanceof ServerPlayer) {
            sendPlayer(command);
        } else if (!(subject instanceof Mob) || !HumanoidFabric.isClaimed(subject)) {
            return false;
        }
        sessions.put(subjectId, session);
        if (previous == null) {
            starts.put(subjectId, session);
            truce(subject, HumanoidFabric.findEntity(UUID.fromString(session.getCasterUuid())));
        }
        if (previous == null || !previous.getOpponentUuid().equals(session.getOpponentUuid())) {
            TechniqueFabric.service().cancelOnUnload(subjectId);
            HumanoidBrain.releaseControl(subjectId);
            if (subject instanceof ServerPlayer player) {
                HumanoidPlayerPath.clear(player);
            }
        }
        if (subject instanceof Mob mob) {
            mob.setTarget(opponent(mob));
        }
        return true;
    }

    private static void truce(LivingEntity subject, LivingEntity caster) {
        // 会话开始：两边互相的敌意记忆清掉；生物施药者放下这个目标，大脑里认的对手、排好的出手一起撤。
        if (caster == null) {
            return;
        }
        HumanoidThreats.forget(subject, caster);
        HumanoidThreats.forget(caster, subject);
        if (caster instanceof Mob mob) {
            if (mob.getTarget() == subject) {
                mob.setTarget(null);
            }
            HumanoidBrain.releaseControl(mob.getUUID());
        }
    }

    private static void validate(CharmCommand command) {
        // 拒绝未知指令和损坏身份，避免把缺省动作当成刷新会话。
        CharmSession session = command.getSession();
        var action = command.getAction();
        var reason = command.getReason();
        long startedTick = session.getStartedTick();
        String opponentId = session.getOpponentUuid();
        if (!command.hasSession() || !command.getUnknownFields().asMap().isEmpty()
                || !session.getUnknownFields().asMap().isEmpty()
                || action != CharmCommand.Action.START && action != CharmCommand.Action.UPDATE
                && action != CharmCommand.Action.END
                || startedTick < 0
                || action != CharmCommand.Action.END && session.getEndsTick() <= startedTick
                || action == CharmCommand.Action.END
                && (reason == CharmEndReason.CHARM_END_UNSPECIFIED || reason == CharmEndReason.UNRECOGNIZED)) {
            throw new IllegalArgumentException("魅惑控制指令或期限无效");
        }
        if (!opponentId.isEmpty()) {
            UUID.fromString(opponentId); // 非空目标必须是合法 UUID。
        }
    }

    private static boolean sameIdentity(CharmSession previous, CharmSession current) {
        // UPDATE 只能换目标、缩短期限，不能改施药者或延长旧会话。
        return previous.getSessionId().equals(current.getSessionId())
                && previous.getCasterUuid().equals(current.getCasterUuid())
                && previous.getSubjectUuid().equals(current.getSubjectUuid())
                && previous.getStartedTick() == current.getStartedTick()
                && current.getEndsTick() <= previous.getEndsTick();
    }

    private static void finish(CharmCommand command, UUID subjectId) {
        // 玩家宿主确认后撤本地控制，失败时保留会话供同一 END 重试。
        LivingEntity subject = HumanoidFabric.findEntity(subjectId);
        if (subject instanceof ServerPlayer player) {
            sendPlayer(command);
            HumanoidPlayerPath.clear(player);
        }
        sessions.remove(subjectId);
        starts.remove(subjectId);
        HumanoidBrain.releaseControl(subjectId);
        TechniqueFabric.service().cancelOnUnload(subjectId);
        if (subject instanceof Mob mob) {
            mob.setTarget(null);
            mob.getNavigation().stop();
        }
    }

    private static void sendPlayer(CharmCommand command) {
        // 协议属于各自职责，Threatening 只做显式映射，不让 Human 反向依赖 Brewing。
        var source = command.getSession();
        String sessionId = source.getSessionId();
        var session = ControlSession.newBuilder()
                .setSessionId(sessionId)
                .setPlayerUuid(source.getSubjectUuid())
                .setCasterUuid(source.getCasterUuid())
                .setStartedTick(source.getStartedTick())
                .setEndsTick(source.getEndsTick())
                .setOpponentUuid(source.getOpponentUuid())
                .build();
        var translated = ControlCommand.newBuilder()
                .setActionValue(command.getActionValue())
                .setSession(session)
                .setReason(command.getReason().name())
                .build();
        try {
            var reply = ControlReply.parseFrom(players.exchange(PlayerControlHost.CALLER, translated.toByteArray()));
            if (!reply.getAccepted() || !reply.getSessionId().equals(sessionId)) {
                throw new IllegalStateException("Human 拒绝受控会话");
            }
        } catch (InvalidProtocolBufferException invalid) {
            throw new IllegalStateException("Human 控制响应无效", invalid);
        }
    }

    static CharmSession session(UUID subject) {
        // 决策入口只能引用已由 Brewing 授权的活动身份。
        return sessions.get(subject);
    }

    public static boolean controlled(LivingEntity subject) {
        // 生物 AI 和目标入口复用同一活动身份。
        return sessions.containsKey(subject.getUUID());
    }

    static LivingEntity opponent(LivingEntity subject) {
        // 目标 UUID 来自 Brewing；实际执行时仍确认存活、同维度和当前阵营。
        CharmSession session = sessions.get(subject.getUUID());
        if (session == null) {
            return null;
        }
        String opponentId = session.getOpponentUuid();
        if (opponentId.isEmpty()) {
            return null;
        }
        LivingEntity target = HumanoidFabric.findEntity(UUID.fromString(opponentId));
        if (target == null || !target.isAlive() || target.level() != subject.level() || protects(subject, target)) {
            return null;
        }
        return target;
    }

    public static boolean protects(LivingEntity subject, Entity target) {
        // 原版近战、远程伤害与目标接口都禁止打施药者、宠物和玩家队友。
        CharmSession session = sessions.get(subject.getUUID());
        if (session == null) {
            return false;
        }
        LivingEntity caster = HumanoidFabric.findEntity(UUID.fromString(session.getCasterUuid()));
        return caster != null && BrewCharmTargets.sameSide(caster, target);
    }

    public static boolean ownSubject(LivingEntity caster, Entity target) {
        // 这只生物现在是不是被我魅惑着。
        CharmSession session = target == null ? null : sessions.get(target.getUUID());
        return session != null && session.getCasterUuid().equals(caster.getStringUUID());
    }

    public static boolean spares(LivingEntity actor, Entity target) {
        // 不该对它出手：我受控时的施药者一方，或者我自己魅惑着的生物。
        return protects(actor, target) || ownSubject(actor, target);
    }

    public static boolean acceptsTarget(Mob subject, LivingEntity target) {
        // 原版选敌任务不能覆盖施药者的目标，凡人仍用原版攻击与导航任务；施药者也不能把自己魅惑着的生物选成目标。
        if (ownSubject(subject, target)) {
            return false;
        }
        return !controlled(subject) || target == opponent(subject);
    }

    static boolean mortal(Mob mob) {
        // 档位取实际身体，不用物种名称猜测是否会修炼。
        var body = ((HumanoidEntityAccess) mob).meridian$humanoidBody();
        return body != null && HumanoidTier.of(body.snapshot()) == HumanoidTier.MORTAL;
    }

    public static boolean follow(Mob mob) {
        // 没有敌人就保持两格跟随距离；原版导航负责避障。
        CharmSession session = sessions.get(mob.getUUID());
        if (session == null || opponent(mob) != null) {
            return false;
        }
        LivingEntity caster = HumanoidFabric.findEntity(UUID.fromString(session.getCasterUuid()));
        if (caster != null && caster.level() == mob.level()) {
            if (mob.distanceToSqr(caster) > FOLLOW_DISTANCE * FOLLOW_DISTANCE) {
                mob.getNavigation().moveTo(caster, FOLLOW_SPEED);
            } else {
                mob.getNavigation().stop();
            }
            mob.getLookControl().setLookAt(caster);
        }
        return true;
    }
}
