package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.Decision;
import dev.kizuna.meridian.humanoid.server.HumanoidAssessment.Stance;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** 一个被接管实体的大脑状态：姿态、对手、当前指令、决策序号、失败屏蔽与最近的决策记录。 */
final class HumanoidMind {
    static final int LOG_CAPACITY = 32;
    private static final double MILLIS_PER_SECOND = 1000;
    private static final long NO_FOLLOW_UP = Long.MAX_VALUE;
    private static final double NO_STRIKE_DISTANCE = -1;
    private final ArrayDeque<Logged> log = new ArrayDeque<>();
    private final Map<String, Failure> failures = new HashMap<>();
    private Stance stance = Stance.ENGAGE;
    private LivingEntity opponent;
    private HumanoidMotion.Directive directive = HumanoidMotion.Directive.RELEASED;
    private CastPlan castPlan;
    private long sequence;
    private boolean adapted;
    private Optional<Vec3> retreatPoint = Optional.empty();
    private boolean cornered;
    private boolean awaitingStaggerEnd;
    private boolean contactPending;
    /** 正在放的这一式，自己会的式里最早到这个身体时刻能接上；没有能接的时候是 NO_FOLLOW_UP。 */
    private long followUpOpensMillis = NO_FOLLOW_UP;
    /** 正在放的这一式后面能接什么已经看过了：一式只读一次身体。 */
    private boolean followUpExamined;
    /** 上次决策时对手还在所有打击式的起手距离外：它进到这个距离以内就补一次决策；没在等的时候是 NO_STRIKE_DISTANCE。 */
    private double strikeDistance = NO_STRIKE_DISTANCE;
    private boolean pathed;
    private int pathedTick;
    private HumanoidPressure.Sighting sighting;
    private long unpressedSinceMillis;
    private String lastFailure = "";
    final HumanoidArmGuard guard = new HumanoidArmGuard();

    Optional<Vec3> retreatPoint() {
        return retreatPoint;
    }

    void planRetreat(Optional<Vec3> destination) {
        // 本次决策与动作执行共用一次寻路结果，不能分别随机选择不同的出口。
        retreatPoint = destination;
        cornered = destination.isEmpty();
    }

    boolean cornered() {
        return cornered;
    }

    void pathed(int tick) {
        // 记下这次寻路发生在实体的第几个 tick。
        pathed = true;
        pathedTick = tick;
    }

    boolean pathedWithin(int tick, int ticks) {
        // 距上次寻路还不到 ticks 个 tick；从没寻过路，或实体 tick 计数被重置过，都算不在间隔内。
        return pathed && tick >= pathedTick && tick - pathedTick < ticks;
    }

    Optional<HumanoidPressure.Sighting> sighting() {
        // 上次决策看到的对手；刚换对手或没有对手时为空。
        return Optional.ofNullable(sighting);
    }

    double notePressure(HumanoidPressure.Sighting seen, boolean pressed) {
        // 记下这次看到的对手，返回撤退中连着多少秒没被它逼着（按身体时钟）；对敌中、刚被逼过都从这一刻重新计。
        sighting = seen;
        if (pressed || stance != Stance.RETREAT) {
            unpressedSinceMillis = seen.millis();
        }
        return (seen.millis() - unpressedSinceMillis) / MILLIS_PER_SECOND;
    }

    void castBlockedByStagger() {
        // 选中的式因僵直没发出去；僵直一结束补一次决策，不白等下一个节拍。
        awaitingStaggerEnd = true;
    }

    boolean awaitingStaggerEnd() {
        return awaitingStaggerEnd;
    }

    void contact() {
        // 原版目标选择先认出了对手、大脑还没有对手；下一个 tick 就决策，不等错相节拍。
        contactPending = true;
    }

    boolean contactPending() {
        return contactPending;
    }

    boolean followUpExamined() {
        return followUpExamined;
    }

    void followUpExamined(OptionalLong opensMillis) {
        // 正在放的这一式看过了：后面有能接的式就记下承接窗口打开的身体时刻，到时补一次决策。
        followUpExamined = true;
        followUpOpensMillis = opensMillis.orElse(NO_FOLLOW_UP);
    }

    void castIdle() {
        // 没有在施放：下一式放出来再重新看。
        followUpExamined = false;
        followUpOpensMillis = NO_FOLLOW_UP;
    }

    boolean awaitingFollowUp() {
        return followUpOpensMillis != NO_FOLLOW_UP;
    }

    boolean takeFollowUp(long clockMillis) {
        // 身体时钟走到了承接窗口打开的时刻：只报这一次。接上以后是新的一式，要重新看它后面还能接什么。
        if (clockMillis < followUpOpensMillis) {
            return false;
        }
        castIdle();
        return true;
    }

    void clearDecisionWaits() {
        // 每次决策都重新判断；这次还被僵直挡住时由施放入口再登记，接敌补决策只补这一次。
        // 等承接窗口的那一条不在这里清：窗口没开以前的例行决策不该把它冲掉。
        awaitingStaggerEnd = false;
        contactPending = false;
        strikeDistance = NO_STRIKE_DISTANCE;
    }

    void awaitStrikeDistance(double distance) {
        // 这次决策时对手还在够得着的距离外：它一进这个距离就补一次决策。
        // 例行节拍十 tick 一次，两边对着走的时候一个节拍能走过一格多，等节拍的话常常已经贴脸，原版近战先打出去了。
        strikeDistance = distance;
    }

    boolean awaitingStrikeDistance() {
        return strikeDistance != NO_STRIKE_DISTANCE;
    }

    boolean takeStrikeDistance(double current) {
        // 对手进了起手距离：只报这一次。
        if (current > strikeDistance) {
            return false;
        }
        strikeDistance = NO_STRIKE_DISTANCE;
        return true;
    }

    /** 一条决策记录：世界 tick、当时的对手与决策。 */
    record Logged(long tick, Optional<LivingEntity> opponent, Decision decision) { }

    /** 同一式的连续失败次数与暂停到的身体毫秒。 */
    private record Failure(int count, long blockedUntilMillis) { }

    /** 蓄力峰值倒排出的下一击计划；只在目标、落点、时间和身体状态仍有效时使用。 */
    record CastPlan(String technique, LivingEntity target, Vec3 expectedLanding, long flightMillis,
                    long firstHitMillis, long dueMillis, long expiresMillis, String reason) { }

    Stance stance() {
        // 上次决策的姿态，供撤退滞回。
        return stance;
    }

    Optional<LivingEntity> opponent() {
        // 当前对手；没有威胁时为空。
        return Optional.ofNullable(opponent);
    }

    HumanoidMotion.Directive directive() {
        // 适配器每 tick 执行的指令。
        return directive;
    }

    long sequence() {
        // 决策序号，参与选式随机种子。
        return sequence;
    }

    void engage(LivingEntity next) {
        // 换对手时姿态从对敌重新评估。
        if (next != opponent) {
            stance = Stance.ENGAGE;
            cornered = false;
            retreatPoint = Optional.empty();
            castPlan = null;
            sighting = null;
        }
        opponent = next;
    }

    void disengage() {
        // 威胁消失，忘掉对手与姿态。
        opponent = null;
        stance = Stance.ENGAGE;
        retreatPoint = Optional.empty();
        cornered = false;
        castPlan = null;
        sighting = null;
    }

    void direct(HumanoidMotion.Directive next) {
        // 换一条移动指令。
        directive = next;
    }

    void release() {
        // 交还原版控制。
        directive = HumanoidMotion.Directive.RELEASED;
        castPlan = null;
    }

    Optional<CastPlan> castPlan() {
        // 当前倒排计划只读暴露给逐 tick 检查器。
        return Optional.ofNullable(castPlan);
    }

    void planCast(CastPlan next) {
        // 同一蓄力施放只登记一次，后续 tick 只验证而不重新抽式。
        if (castPlan == null) {
            castPlan = next;
        }
    }

    void clearCastPlan() {
        // 目标切换、受击、超时或施放失败时清理倒排计划。
        castPlan = null;
    }

    void decided(long tick, Stance next, Decision decision) {
        // 记下姿态与决策，只保留最近若干条。
        stance = next;
        sequence++;
        if (log.size() == LOG_CAPACITY) {
            log.removeFirst();
        }
        log.addLast(new Logged(tick, opponent(), decision));
    }

    List<Logged> log() {
        // 最近的决策，旧的在前。
        return List.copyOf(log);
    }

    boolean blocked(String technique, long nowMillis) {
        // 连续失败达上限且仍在暂停期内的式不再尝试。
        Failure failure = failures.get(technique);
        return failure != null && nowMillis < failure.blockedUntilMillis();
    }

    void failed(String technique, long nowMillis, int limit, long blockMillis) {
        // 累计连续失败；达到上限时从现在起暂停。
        Failure previous = failures.getOrDefault(technique, new Failure(0, Long.MIN_VALUE));
        int count = previous.count() + 1;
        long blockedUntil = previous.blockedUntilMillis();
        if (count >= limit) {
            blockedUntil = nowMillis + blockMillis;
        }
        failures.put(technique, new Failure(count, blockedUntil));
    }

    void noteFailure(String detail) {
        // 最近一次施放失败的原因，排查用。
        lastFailure = detail;
    }

    String lastFailure() {
        return lastFailure;
    }

    void succeeded(String technique) {
        // 成功一次就清零。
        failures.remove(technique);
    }

    int failureStreak() {
        // 各式当前连续失败次数中的最大值。
        return failures.values().stream().mapToInt(Failure::count).max().orElse(0);
    }

    boolean adapted() {
        // 接入适配器是否已装好。
        return adapted;
    }

    void markAdapted() {
        // 每个实体对象只装一次。
        adapted = true;
    }
}
