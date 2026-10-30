package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.humanoid.server.Encounter.Incoming;
import dev.kizuna.meridian.humanoid.server.StrikeGeometry;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import dev.kizuna.meridian.technique.fabric.world.TechniqueWorldEffects;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveCast;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItemRules;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.InjuryKind;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StrikeShape;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WeaponStrike;
import dev.kizuna.meridian.technique.server.TechniqueForecasts;
import dev.kizuna.meridian.vanilla.MuscleRules;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.Optional;

/** 按 Damage 同一规则估算打击：护甲前剂量乘肌力系数换成身体 HP，再求僵直；以及来袭打击是否覆盖我、往哪走出。 */
final class HumanoidStrikeEstimates {
    private static final double SINGLE_TARGET_HALF_ANGLE_DEGREES = 30;
    private static final double HALF = 0.5;

    /** 一次打击的身体 HP 剂量与致伤种类。 */
    record Dose(double hp, InjuryKind injury) { }

    /** 走出来袭范围的距离与落脚点。 */
    record Exit(double distance, Vec3d point) { }

    private HumanoidStrikeEstimates() {
        // 纯估算，不提交做功或伤害。
    }

    static Optional<Dose> dose(LivingEntity actor, BodySnapshot actorBody, Hand hand, HeldItemRules rules,
                               WeaponStrike strike, LivingEntity target) {
        // 空手或装物容器估不出剂量；肌力系数读出手者现有快照。
        var estimate = TechniqueWorldEffects.estimate(actor, hand, rules, strike, target);
        if (estimate.isEmpty()) {
            return Optional.empty();
        }
        var grip = grip(hand, estimate.get());
        double coefficient = VanillaDamageFabric.estimateStrikeCoefficient(BodyProtobuf.toMessage(actorBody),
                actor, grip);
        double hp = VanillaDamageFabric.preferences().damageHp(estimate.get().dose() * coefficient);
        return Optional.of(new Dose(hp, estimate.get().injury()));
    }

    private static MuscleRules.Grip grip(Hand hand, TechniqueWorldEffects.DoseEstimate estimate) {
        // 双手握持优先，其次按选定手。
        if (estimate.twoHanded()) {
            return MuscleRules.Grip.TWO_HANDED;
        }
        if (hand == Hand.OFF_HAND) {
            return MuscleRules.Grip.OFF_HAND;
        }
        return MuscleRules.Grip.MAIN_HAND;
    }

    static long staggerMillis(Dose dose, BodySnapshot target) {
        // 以目标最大的部位为命中点，是偏保守的估计；伤害种类与致伤种类同名。
        Anatomy.PartDefinition largest = target.structure().parts().stream()
                .max(Comparator.comparingDouble(Anatomy.PartDefinition::maxHp))
                .orElseThrow();
        var hit = HealthTarget.newBuilder()
                .setPartId(largest.id().value())
                .build();
        var kind = DamageKind.valueOf(dose.injury().name());
        return VanillaDamageFabric.preferences().stagger().millis(BodyProtobuf.toMessage(target), hit, kind,
                dose.hp());
    }

    static Optional<Incoming> incoming(LivingEntity self, BodySnapshot selfBody, HumanoidOpponent opponent) {
        // 对方最早一击；没有打击的起手也给出来袭，供打断判断，只是不覆盖我。
        var cast = opponent.cast();
        if (cast.isEmpty()) {
            return Optional.empty();
        }
        var forecast = cast.get();
        var traits = EnumSet.noneOf(Incoming.Trait.class);
        if (forecast.interruptible()) {
            traits.add(Incoming.Trait.INTERRUPTIBLE);
        }
        var next = next(forecast);
        if (next.isEmpty()) {
            return Optional.of(new Incoming(Long.MAX_VALUE, forecast.millisUntilWarmupEnd(), 0, traits));
        }
        var window = next.get();
        if (covers(self, opponent.entity(), forecast.active(), window.strike())) {
            traits.add(Incoming.Trait.COVERS);
        }
        double ratio = doseRatio(self, selfBody, opponent, forecast.active(), window.strike());
        return Optional.of(new Incoming(window.millisUntilHit(), forecast.millisUntilWarmupEnd(), ratio, traits));
    }

    private static Optional<TechniqueForecasts.StrikeWindow> next(TechniqueForecasts.CastForecast forecast) {
        // 未来最先命中的一击。
        return forecast.strikes().stream()
                .min(Comparator.comparingLong(TechniqueForecasts.StrikeWindow::millisUntilHit));
    }

    private static double doseRatio(LivingEntity self, BodySnapshot selfBody, HumanoidOpponent opponent,
                                    ActiveCast active, WeaponStrike strike) {
        // 预计剂量占我最弱存活部位当前 HP；估不出剂量时为 0。
        double weakest = HumanoidFighters.weakestPartHp(selfBody);
        var observation = opponent.observation().orElseThrow();
        var dose = dose(opponent.entity(), observation.body(), active.getSelectedHand(), active.getHeldItemRules(),
                strike, self);
        if (dose.isEmpty()) {
            return 0;
        }
        if (weakest <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return dose.get().hp() / weakest;
    }

    static boolean covers(LivingEntity self, LivingEntity caster, ActiveCast active, WeaponStrike strike) {
        // 单体打击只覆盖它瞄准的人（没写目标时覆盖锥内任何人）；扇形按水平几何。
        String targetId = active.getRequest().getTargetId();
        if (strike.getShape() == StrikeShape.SINGLE_TARGET && !targetId.isEmpty()
                && !targetId.equals(self.getUuidAsString())) {
            return false;
        }
        return StrikeGeometry.covers(sector(caster, strike), point(self.getPos()));
    }

    static Optional<Exit> exit(LivingEntity self, HumanoidOpponent opponent) {
        // 最早一击覆盖我时，沿背离对方的方向走出范围。
        var cast = opponent.cast();
        var next = cast.flatMap(HumanoidStrikeEstimates::next);
        if (next.isEmpty()) {
            return Optional.empty();
        }
        WeaponStrike strike = next.get().strike();
        if (!covers(self, opponent.entity(), cast.get().active(), strike)) {
            return Optional.empty();
        }
        var sector = sector(opponent.entity(), strike);
        double distance = StrikeGeometry.exitDistance(sector, point(self.getPos()));
        Vec3d away = HumanoidMotion.away(self, opponent.entity());
        return Optional.of(new Exit(distance, self.getPos().add(away.multiply(distance))));
    }

    private static StrikeGeometry.Sector sector(LivingEntity caster, WeaponStrike strike) {
        // 朝向为施放者 yaw 加目录朝向偏移。
        double facing = caster.getYaw() + strike.getFacingOffset().getValue();
        return new StrikeGeometry.Sector(point(caster.getPos()), facing, strike.getDistance().getValue(),
                halfAngle(strike));
    }

    private static double halfAngle(WeaponStrike strike) {
        // 单体打击按 60° 锥计；扇形取目录张角的一半。
        if (strike.getShape() == StrikeShape.SECTOR) {
            return strike.getAngle().getValue() * HALF;
        }
        return SINGLE_TARGET_HALF_ANGLE_DEGREES;
    }

    private static StrikeGeometry.Point point(Vec3d position) {
        // 水平面坐标。
        return new StrikeGeometry.Point(position.x, position.z);
    }
}
