package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.humanoid.server.Decision.Action;
import dev.kizuna.meridian.humanoid.server.Encounter.Circumstance;
import dev.kizuna.meridian.humanoid.server.Encounter.Incoming;
import dev.kizuna.meridian.humanoid.server.HumanoidAssessment.Stance;
import dev.kizuna.meridian.humanoid.server.Repertoire.Role;
import dev.kizuna.meridian.humanoid.server.Repertoire.Strike;
import dev.kizuna.meridian.humanoid.server.Repertoire.Support;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/** 交战战术：先应对来袭打击，再按对敌或撤退选择准备、突击、拉扯、出手或走开；只认目录字段，不认功法 ID。 */
public final class HumanoidTactics {
    private static final Set<Role> ENGAGE_SUPPORT = EnumSet.of(Role.PREPARE, Role.REPOSITION);
    private static final Set<Role> RETREAT_SUPPORT = EnumSet.of(Role.REPOSITION);
    private static final Set<Role> RECOVER_SUPPORT = EnumSet.of(Role.RECOVER);

    private HumanoidTactics() {
        // 纯函数集合，不持有实体或随机源。
    }

    public static Decision decide(Encounter encounter, Stance stance, Repertoire repertoire, TacticRules rules) {
        // 飞行中不改主意；来袭应对优先，施放中只能躲或抗，其余按姿态展开。
        if (encounter.has(Circumstance.LEAPING)) {
            return Decision.of(Action.HOLD, "跃步飞行中");
        }
        Optional<Decision> response = respond(encounter, stance, repertoire, rules.reaction());
        if (response.isPresent()) {
            return response.get();
        }
        if (encounter.self().has(Fighter.Means.CASTING)) {
            return Decision.of(Action.HOLD, "施放中");
        }
        if (stance == Stance.RETREAT) {
            return retreat(encounter, repertoire, rules.safeDistance());
        }
        return engage(encounter, repertoire);
    }

    public static Decision idle(Fighter self, Repertoire repertoire) {
        // 没有威胁时只考虑恢复式，否则把控制交还原版日常。
        return support(self, repertoire, RECOVER_SUPPORT, OptionalDouble.empty())
                .map(chosen -> Decision.cast(Action.RECOVER, chosen.technique(), "无威胁且疲劳达门槛，施放恢复式"))
                .orElseGet(() -> Decision.of(Action.RELEASE, "无威胁"));
    }

    public static boolean ready(Support support, Fighter self, OptionalDouble distance,
                                long lightBodyRemainingMillis) {
        // 距离、真元、疲劳与持物逐项比较；无威胁时不比距离；机动式在轻身剩余不足补放门槛时才放。
        var gate = support.gate();
        if (distance.isPresent() && !within(distance.getAsDouble(), gate)) {
            return false;
        }
        if (self.condition().qiRatio() < gate.minimumQiRatio()
                || gate.fatigue().current() < gate.fatigue().minimum()) {
            return false;
        }
        if (gate.requirements().contains(Repertoire.Requirement.MELEE_WEAPON)
                && !self.has(Fighter.Means.MELEE_WEAPON)) {
            return false;
        }
        return support.role() != Role.REPOSITION || lightBodyRemainingMillis <= support.refreshBeforeExpiryMillis();
    }

    private static boolean within(double distance, Repertoire.Gate gate) {
        // 距离落在门槛闭区间内。
        return distance >= gate.minimumDistance() && distance <= gate.maximumDistance();
    }

    private static Optional<Decision> respond(Encounter encounter, Stance stance, Repertoire repertoire,
                                              TacticRules.Reaction reaction) {
        // 对敌时先找能抢在对方起手结束前打出僵直的式；覆盖我的来袭进入反应窗口后再判断抗或躲。
        if (encounter.incoming().isEmpty()) {
            return Optional.empty();
        }
        Incoming incoming = encounter.incoming().get();
        boolean casting = encounter.self().has(Fighter.Means.CASTING);
        if (!casting && stance == Stance.ENGAGE && incoming.has(Incoming.Trait.INTERRUPTIBLE)) {
            Optional<Decision> interrupt = interrupt(encounter.distance(), incoming, repertoire.strikes(), reaction);
            if (interrupt.isPresent()) {
                return interrupt;
            }
        }
        long hit = incoming.millisUntilHit();
        if (!incoming.has(Incoming.Trait.COVERS) || hit > reaction.windowMillis()) {
            return Optional.empty();
        }
        long ownHit = repertoire.timing().ownNextHitMillis();
        if (casting && ownHit <= reaction.absorbOwnHitMillis()) {
            return Optional.of(Decision.of(Action.ABSORB, format("自己的式 %d ms 后先命中，硬抗", ownHit)));
        }
        if (incoming.doseRatio() < reaction.absorbDoseRatio()) {
            return Optional.of(Decision.of(Action.ABSORB, format("预计剂量比 %.2f 偏小，硬抗", incoming.doseRatio())));
        }
        long exit = repertoire.timing().exitMillis();
        if (exit + reaction.dodgeMarginMillis() < hit) {
            return Optional.of(Decision.of(Action.DODGE, format("来袭 %d ms 后命中，%d ms 可走出范围", hit, exit)));
        }
        return Optional.of(Decision.of(Action.ABSORB, format("来袭 %d ms 后命中，来不及走出范围，硬抗", hit)));
    }

    private static Optional<Decision> interrupt(double distance, Incoming incoming, List<Strike> strikes,
                                                TacticRules.Reaction reaction) {
        // 只用能造成僵直、距离够、首击加余量早于对方起手结束的式，取首击最快者。
        long deadline = incoming.millisUntilWarmupEnd() - reaction.interruptMarginMillis();
        return strikes.stream()
                .filter(strike -> strike.staggerMillis() > 0 && distance <= strike.reach()
                        && strike.firstHitMillis() < deadline)
                .min(Comparator.comparingLong(Strike::firstHitMillis))
                .map(strike -> Decision.cast(Action.INTERRUPT, strike.technique(),
                        format("对方起手剩余 %d ms 可打断，%s 首击 %d ms 先到", incoming.millisUntilWarmupEnd(),
                                strike.technique(), strike.firstHitMillis())));
    }

    private static Decision retreat(Encounter encounter, Repertoire repertoire, double safeDistance) {
        // 到安全距离就恢复或等待；未到时轻身在身先背向跃步，其次补放机动式，最后走开。
        var distance = OptionalDouble.of(encounter.distance());
        Fighter self = encounter.self();
        if (encounter.distance() >= safeDistance) {
            return support(self, repertoire, RECOVER_SUPPORT, distance)
                    .map(chosen -> Decision.cast(Action.RECOVER, chosen.technique(), "已拉开到安全距离，施放恢复式"))
                    .orElseGet(() -> Decision.of(Action.HOLD, "已拉开到安全距离"));
        }
        if (repertoire.timing().lightBodyRemainingMillis() > 0 && encounter.has(Circumstance.ON_GROUND)) {
            return Decision.of(Action.KITE, "轻身在身，背向跃步拉开");
        }
        return support(self, repertoire, RETREAT_SUPPORT, distance)
                .map(chosen -> Decision.cast(Action.PREPARE, chosen.technique(), "施放机动式以便拉开"))
                .orElseGet(() -> Decision.of(Action.RETREAT, "走开拉开距离"));
    }

    private static Decision engage(Encounter encounter, Repertoire repertoire) {
        // 未挨打时按顺序补准备与机动式；持近战武器且轻身在身、在打击距离外就突击；进入打击距离才出手。
        double distance = encounter.distance();
        Fighter self = encounter.self();
        if (!encounter.has(Circumstance.UNDER_ATTACK)) {
            Optional<Support> prepare = support(self, repertoire, ENGAGE_SUPPORT, OptionalDouble.of(distance));
            if (prepare.isPresent()) {
                return Decision.cast(Action.PREPARE, prepare.get().technique(), "接敌前准备");
            }
        }
        double reach = repertoire.strikes().stream().mapToDouble(Strike::reach).max().orElse(0);
        var timing = repertoire.timing();
        if (timing.lightBodyRemainingMillis() > 0 && encounter.has(Circumstance.ON_GROUND)
                && self.has(Fighter.Means.MELEE_WEAPON) && distance > reach
                && distance >= timing.leapMinimumDistance()) {
            return Decision.of(Action.ASSAULT, format("轻身在身，距离 %.1f 格，跃步突击", distance));
        }
        if (distance <= reach) {
            return Decision.of(Action.STRIKE, format("%.1f 格内有打击式", reach));
        }
        return Decision.of(Action.APPROACH, "接近目标");
    }

    private static Optional<Support> support(Fighter self, Repertoire repertoire, Set<Role> roles,
                                             OptionalDouble distance) {
        // 按 order 从小到大取第一项门槛满足的辅助式。
        long lightBody = repertoire.timing().lightBodyRemainingMillis();
        return repertoire.supports().stream()
                .filter(candidate -> roles.contains(candidate.role()))
                .filter(candidate -> ready(candidate, self, distance, lightBody))
                .min(Comparator.comparingInt(Support::order));
    }

    private static String format(String pattern, Object... values) {
        // 理由文本与区域设置无关。
        return String.format(Locale.ROOT, pattern, values);
    }
}
