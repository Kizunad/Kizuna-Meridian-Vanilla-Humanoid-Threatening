package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.Decision;
import dev.kizuna.meridian.humanoid.server.HumanoidAssessment.Stance;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** 一个被接管实体的大脑状态：姿态、对手、当前指令、决策序号、失败屏蔽与最近的决策记录。 */
final class HumanoidMind {
    static final int LOG_CAPACITY = 32;
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
    private boolean pathed;
    private int pathedTick;

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

    void clearDecisionWaits() {
        // 每次决策都重新判断；这次还被僵直挡住时由施放入口再登记，接敌补决策只补这一次。
        awaitingStaggerEnd = false;
        contactPending = false;
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
