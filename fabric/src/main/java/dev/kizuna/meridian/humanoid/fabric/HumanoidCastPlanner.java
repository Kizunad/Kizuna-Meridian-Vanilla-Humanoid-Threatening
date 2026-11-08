package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.humanoid.server.Repertoire;
import dev.kizuna.meridian.protocol.pb.BodyMessages.CurvePoint;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveCast;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.AiRole;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TissueModifier;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeaps;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.meridian.technique.server.TechniqueSelector;

import java.util.Comparator;
import java.util.Optional;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/** 蓄力峰值倒排器：从当前目录曲线和候选首击时刻计算一次性的起手计划。 */
final class HumanoidCastPlanner {
    private static final double PEAK_EPSILON = 0.000001;
    private static final double PEAK_MIDPOINT_RATIO = 0.5;
    private static final long PEAK_EDGE_TOLERANCE_MILLIS = 50;

    private HumanoidCastPlanner() {
        // 计划只保存到 HumanoidMind，不在规划器中缓存身体或世界状态。
    }

    static Optional<HumanoidMind.CastPlan> prepare(Mob mob, BodySnapshot body,
                                                    HumanoidRepertoires.Readout readout,
                                                    HumanoidOpponent opponent, HumanoidMind mind) {
        // 只有当前施放属于准备角色、轻身有效且能预报落点时才倒排下一次打击。
        Optional<ActiveCast> active = activePrepare(body);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        var registry = TechniqueFabric.service().registry();
        Optional<TechniqueDefinition> definition = registry.find(active.get().getRequest().getTechnique());
        Optional<PeakWindow> peak = definition.flatMap(HumanoidCastPlanner::peakWindow);
        Optional<Repertoire.Strike> strike = chooseStrike(mob, readout, mind);
        if (peak.isEmpty() || strike.isEmpty() || readout.repertoire().timing().lightBodyRemainingMillis() <= 0) {
            return Optional.empty();
        }
        Vec3 expectedLanding = HumanoidActions.assaultLanding(mob, opponent.entity());
        Optional<TechniqueLeaps.Preview> preview = TechniqueLeaps.preview(mob, expectedLanding);
        if (preview.isEmpty()) {
            return Optional.empty();
        }
        PeakWindow window = peak.get();
        long peakStart = active.get().getStartedMillis() + window.startMillis();
        long peakEnd = active.get().getStartedMillis() + window.endMillis();
        long targetHit = midpoint(peakStart, peakEnd);
        long desired = targetHit - strike.get().firstHitMillis();
        long due = Math.max(active.get().getRecoveryEndMillis(), desired);
        long expires = peakEnd - strike.get().firstHitMillis() + PEAK_EDGE_TOLERANCE_MILLIS;
        long now = body.settledAt().elapsedMillis();
        if (strike.get().firstHitMillis() < preview.get().flightMillis()
                || peakEnd + PEAK_EDGE_TOLERANCE_MILLIS < now || due > expires) {
            return Optional.empty();
        }
        String reason = "按当前准备曲线峰值倒排，" + strike.get().technique()
                + " 首击 " + strike.get().firstHitMillis() + " ms";
        return Optional.of(new HumanoidMind.CastPlan(strike.get().technique(), opponent.entity(), expectedLanding,
                preview.get().flightMillis(), strike.get().firstHitMillis(), due, expires, reason));
    }

    private static Optional<ActiveCast> activePrepare(BodySnapshot body) {
        // 角色只看目录提示；没有活动施放或不是准备式时不创建计划。
        var progress = TechniqueState.read(body);
        if (!progress.hasActiveCast()) {
            return Optional.empty();
        }
        ActiveCast active = progress.getActiveCast();
        var registry = TechniqueFabric.service().registry();
        return registry.find(active.getRequest().getTechnique())
                .filter(definition -> definition.getAiHints().getSupport().getRole() == AiRole.AI_ROLE_PREPARE)
                .map(ignored -> active);
    }

    private static long midpoint(long startMillis, long endMillis) {
        // 用无溢出的差值取峰值平台中点，避免长身体时钟相加溢出。
        return startMillis + Math.round((endMillis - startMillis) * PEAK_MIDPOINT_RATIO);
    }

    private static Optional<Repertoire.Strike> chooseStrike(Mob mob, HumanoidRepertoires.Readout readout,
                                                              HumanoidMind mind) {
        // 选择器只抽一次，再映射到本轮已验证的首击事实。
        return TechniqueSelector.choose(readout.context(), readout.strikes(), HumanoidBrain.seed(mob, mind))
                .flatMap(reference -> readout.repertoire().strikes().stream()
                        .filter(strike -> strike.technique().equals(reference.getId()))
                        .min(Comparator.comparingLong(Repertoire.Strike::firstHitMillis)));
    }

    private static Optional<PeakWindow> peakWindow(TechniqueDefinition definition) {
        // 只认力量倍率大于 1 的组织曲线，取最早出现的最大曲线平台。
        PeakWindow result = null;
        for (TechniqueEffect effect : definition.getEffectsList()) {
            if (!effect.hasStatusBenefit() || !effect.getStatusBenefit().hasTissue()) {
                continue;
            }
            for (TissueModifier modifier : effect.getStatusBenefit().getTissue().getModifiersList()) {
                if (modifier.getStrengthPeak().getValue() <= 1 || modifier.getCurveCount() == 0) {
                    continue;
                }
                PeakWindow candidate = curvePeak(effect, modifier);
                if (result == null || candidate.startMillis() < result.startMillis()) {
                    result = candidate;
                }
            }
        }
        return Optional.ofNullable(result);
    }

    private static PeakWindow curvePeak(TechniqueEffect effect, TissueModifier modifier) {
        // 曲线值达到最大值的连续平台就是有效峰值窗口；端点保留一个 tick 的命中余量。
        double maximum = modifier.getCurveList().stream().mapToDouble(CurvePoint::getValue).max().orElse(0);
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        for (CurvePoint point : modifier.getCurveList()) {
            if (Math.abs(point.getValue() - maximum) <= PEAK_EPSILON) {
                first = Math.min(first, point.getOffsetMillis());
                last = Math.max(last, point.getOffsetMillis());
            }
        }
        return new PeakWindow(effect.getAtMillis() + first, effect.getAtMillis() + last);
    }

    private record PeakWindow(long startMillis, long endMillis) { }
}
