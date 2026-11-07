package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.humanoid.server.Decision.Action;
import dev.kizuna.meridian.humanoid.server.Encounter.Circumstance;
import dev.kizuna.meridian.humanoid.server.Encounter.Incoming;
import dev.kizuna.meridian.humanoid.server.Fighter.Condition;
import dev.kizuna.meridian.humanoid.server.Fighter.Means;
import dev.kizuna.meridian.humanoid.server.HumanoidAssessment.Stance;
import dev.kizuna.meridian.humanoid.server.Repertoire.Fatigue;
import dev.kizuna.meridian.humanoid.server.Repertoire.Gate;
import dev.kizuna.meridian.humanoid.server.Repertoire.Requirement;
import dev.kizuna.meridian.humanoid.server.Repertoire.Role;
import dev.kizuna.meridian.humanoid.server.Repertoire.Strike;
import dev.kizuna.meridian.humanoid.server.Repertoire.Support;
import dev.kizuna.meridian.humanoid.server.Repertoire.Timing;
import org.junit.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 默认应对阈值下的战术判定：来袭应对、对敌展开、撤退与无威胁恢复。 */
public class HumanoidTacticsTest {
    private static final TacticRules RULES = HumanoidBrainConfigLoader.defaults().response().tactics();
    private static final String KNOCK = "test:knock";
    private static final String CHARGE = "test:charge";
    private static final String LIGHT_STEP = "test:light_step";
    private static final String BREATH = "test:breath";
    private static final long KNOCK_FIRST_HIT = 600;
    private static final long SLOW_FIRST_HIT = 800;
    private static final double KNOCK_REACH = 3;
    private static final long KNOCK_STAGGER = 400;
    private static final long INCOMING_HIT = 800;
    private static final long FAR_INCOMING_HIT = 1500;
    private static final double HEAVY_DOSE = 0.5;
    private static final double LIGHT_DOSE = 0.1;
    private static final long QUICK_EXIT = 300;
    private static final long SLOW_EXIT = 750;
    private static final long OWN_HIT_SOON = 200;
    private static final long NO_OWN_HIT = Long.MAX_VALUE;
    private static final long LIGHT_BODY = 5000;
    private static final long LIGHT_BODY_EXPIRING = 2000;
    private static final long REFRESH = 3000;
    private static final double LEAP_MINIMUM = 3;
    private static final double CLOSE = 2.5;
    private static final double MID = 6;
    private static final double FAR = 10;
    private static final double SAFE = 12;
    private static final double CHARGE_MINIMUM = 4;
    private static final double CHARGE_MAXIMUM = 24;
    private static final double CHARGE_QI = 0.2;
    private static final double BREATH_MAXIMUM = 1024;
    private static final double BREATH_QI = 0.1;
    private static final double BREATH_FATIGUE = 0.3;
    private static final double TIRED = 0.5;
    private static final double FRESH = 0.1;
    private static final double DRAINED_QI = 0.05;
    private static final int CHARGE_ORDER = 1;
    private static final int LIGHT_STEP_ORDER = 2;
    private static final Condition FULL = new Condition(1, 1, 1, 1, 1);
    private static final Fighter AXE = new Fighter(1, FULL, EnumSet.of(Means.CASTABLE_STRIKE, Means.MELEE_WEAPON));
    private static final Fighter AXE_CASTING = new Fighter(1, FULL,
            EnumSet.of(Means.CASTABLE_STRIKE, Means.MELEE_WEAPON, Means.CASTING));
    private static final Fighter BARE = new Fighter(1, FULL, EnumSet.of(Means.CASTABLE_STRIKE));
    private static final Strike KNOCK_STRIKE = new Strike(KNOCK, KNOCK_FIRST_HIT, KNOCK_REACH, KNOCK_STAGGER);
    private static final Set<Incoming.Trait> OPEN_WARMUP = EnumSet.of(Incoming.Trait.COVERS,
            Incoming.Trait.INTERRUPTIBLE);

    @Test
    public void interruptsWhenStaggeringStrikeLandsFirst() {
        // 首击 600 ms 早于起手剩余 800 减余量 150；同样处境下不能僵直、首击太慢或在撤退中都改为躲。
        var incoming = new Incoming(INCOMING_HIT, INCOMING_HIT, HEAVY_DOSE, OPEN_WARMUP);
        Decision interrupt = decide(encounter(AXE, CLOSE, incoming), Stance.ENGAGE, strikes(KNOCK_STRIKE));
        assertEquals(Action.INTERRUPT, interrupt.action());
        assertEquals(Optional.of(KNOCK), interrupt.technique());
        var weak = new Strike(KNOCK, KNOCK_FIRST_HIT, KNOCK_REACH, 0);
        var slow = new Strike(KNOCK, SLOW_FIRST_HIT, KNOCK_REACH, KNOCK_STAGGER);
        assertEquals(Action.DODGE, decide(encounter(AXE, CLOSE, incoming), Stance.ENGAGE, strikes(weak)).action());
        assertEquals(Action.DODGE, decide(encounter(AXE, CLOSE, incoming), Stance.ENGAGE, strikes(slow)).action());
        Decision retreating = decide(encounter(AXE, CLOSE, incoming), Stance.RETREAT, strikes(KNOCK_STRIKE));
        assertEquals(Action.DODGE, retreating.action());
    }

    @Test
    public void absorbsLightOrUnavoidableHits() {
        // 剂量比低于 0.15、自己的式 300 ms 内先到、或走出范围赶不上命中时都硬抗。
        var light = new Incoming(INCOMING_HIT, 0, LIGHT_DOSE, Set.of(Incoming.Trait.COVERS));
        var heavy = new Incoming(INCOMING_HIT, 0, HEAVY_DOSE, Set.of(Incoming.Trait.COVERS));
        var ownHitSoon = new Repertoire(List.of(KNOCK_STRIKE), List.of(), timing(0, OWN_HIT_SOON, QUICK_EXIT));
        var slowExit = new Repertoire(List.of(KNOCK_STRIKE), List.of(), timing(0, NO_OWN_HIT, SLOW_EXIT));
        assertEquals(Action.ABSORB, decide(encounter(AXE, CLOSE, light), Stance.ENGAGE, strikes()).action());
        assertEquals(Action.ABSORB, decide(encounter(AXE_CASTING, CLOSE, heavy), Stance.ENGAGE, ownHitSoon).action());
        assertEquals(Action.ABSORB, decide(encounter(AXE, CLOSE, heavy), Stance.ENGAGE, slowExit).action());
    }

    @Test
    public void ignoresHitsOutsideWindowOrSector() {
        // 命中晚于反应窗口或范围不覆盖我时照常出手。
        var late = new Incoming(FAR_INCOMING_HIT, 0, HEAVY_DOSE, Set.of(Incoming.Trait.COVERS));
        var aside = new Incoming(INCOMING_HIT, 0, HEAVY_DOSE, Set.of());
        var afterWindow = decide(encounter(AXE, CLOSE, late), Stance.ENGAGE, strikes(KNOCK_STRIKE));
        var outsideSector = decide(encounter(AXE, CLOSE, aside), Stance.ENGAGE, strikes(KNOCK_STRIKE));
        assertEquals(Action.STRIKE, afterWindow.action());
        assertEquals(Action.STRIKE, outsideSector.action());
    }

    @Test
    public void castingOnlyDodgesOrHolds() {
        // 施放中不打断对方；来袭覆盖时可躲，没有来袭就保持。
        var incoming = new Incoming(INCOMING_HIT, INCOMING_HIT, HEAVY_DOSE, OPEN_WARMUP);
        Decision dodge = decide(encounter(AXE_CASTING, CLOSE, incoming), Stance.ENGAGE, strikes(KNOCK_STRIKE));
        Decision hold = decide(calm(AXE_CASTING, CLOSE), Stance.ENGAGE, strikes(KNOCK_STRIKE));
        assertEquals(Action.DODGE, dodge.action());
        assertEquals(Action.HOLD, hold.action());
    }

    @Test
    public void leapingHolds() {
        // 跃步飞行中什么都不改。
        var leaping = new Encounter(AXE, AXE, CLOSE, Optional.empty(), Set.of(Circumstance.LEAPING));
        assertEquals(Action.HOLD, decide(leaping, Stance.ENGAGE, strikes(KNOCK_STRIKE)).action());
    }

    @Test
    public void preparesInCatalogOrderBeforeEngaging() {
        // 接敌前先放 order 小的蓄力，再放轻步；轻身剩余高于补放门槛时轻步不再放，改为突击。
        var both = supports(0, charge(), lightStep());
        var stepOnly = supports(0, lightStep());
        var lit = supports(LIGHT_BODY, lightStep());
        Decision charge = decide(calm(AXE, FAR), Stance.ENGAGE, both);
        Decision step = decide(calm(AXE, FAR), Stance.ENGAGE, stepOnly);
        assertEquals(Action.PREPARE, charge.action());
        assertEquals(Optional.of(CHARGE), charge.technique());
        assertEquals(Optional.of(LIGHT_STEP), step.technique());
        assertEquals(Action.ASSAULT, decide(calm(AXE, FAR), Stance.ENGAGE, lit).action());
    }

    @Test
    public void meleeRequirementAndPressureSkipPreparation() {
        // 空手不放要求近战武器的蓄力；挨打时不补准备，直接接近。
        var chargeOnly = supports(0, charge());
        var underAttack = new Encounter(AXE, AXE, FAR, Optional.empty(),
                EnumSet.of(Circumstance.ON_GROUND, Circumstance.UNDER_ATTACK));
        assertEquals(Action.APPROACH, decide(calm(BARE, FAR), Stance.ENGAGE, chargeOnly).action());
        assertEquals(Action.APPROACH, decide(underAttack, Stance.ENGAGE, chargeOnly).action());
    }

    @Test
    public void assaultNeedsLightBodyMeleeAndDistance() {
        // 轻身在身、持近战武器、在打击距离外且不小于跃步最小距离才突击，进入打击距离就出手。
        var lit = supports(LIGHT_BODY);
        assertEquals(Action.ASSAULT, decide(calm(AXE, MID), Stance.ENGAGE, lit).action());
        assertEquals(Action.APPROACH, decide(calm(BARE, MID), Stance.ENGAGE, lit).action());
        assertEquals(Action.APPROACH, decide(calm(AXE, MID), Stance.ENGAGE, supports(0)).action());
        assertEquals(Action.STRIKE, decide(calm(AXE, CLOSE), Stance.ENGAGE, lit).action());
    }

    @Test
    public void retreatKitesRepositionsOrWalks() {
        // 撤退未到安全距离：轻身在身就背向跃步，否则先放机动式，都没有就走开。
        assertEquals(Action.KITE, decide(calm(AXE, MID), Stance.RETREAT, supports(LIGHT_BODY)).action());
        Decision reposition = decide(calm(AXE, MID), Stance.RETREAT, supports(0, lightStep()));
        assertEquals(Action.PREPARE, reposition.action());
        assertEquals(Optional.of(LIGHT_STEP), reposition.technique());
        assertEquals(Action.RETREAT, decide(calm(AXE, MID), Stance.RETREAT, supports(0)).action());
    }

    @Test
    public void retreatStillDefendsWhenCorneredOrPursuedAtCloseRange() {
        // 撤退姿态不屏蔽已经通过资源门槛的防守式；没有退路的原版攻击者也要恢复自卫。
        var cornered = new Encounter(AXE, AXE, CLOSE, Optional.empty(),
                Set.of(Circumstance.ON_GROUND, Circumstance.RETREAT_BLOCKED));
        var defensive = new Encounter(BARE, AXE, CLOSE, Optional.empty(),
                Set.of(Circumstance.ON_GROUND, Circumstance.DEFENSIVE_STRIKE));
        assertEquals(Action.STRIKE, decide(cornered, Stance.RETREAT, strikes(KNOCK_STRIKE)).action());
        assertEquals(Action.STRIKE, decide(defensive, Stance.RETREAT, strikes(KNOCK_STRIKE)).action());
        assertEquals(Action.HOLD, decide(cornered, Stance.RETREAT, strikes()).action());
        var nativeFighter = new Fighter(1, FULL, Set.of(Means.VANILLA_OFFENSE));
        var nativeCornered = new Encounter(nativeFighter, AXE, CLOSE, Optional.empty(), cornered.circumstances());
        assertEquals(Action.APPROACH, decide(nativeCornered, Stance.RETREAT, strikes()).action());
        var exhausted = new Encounter(nativeFighter, AXE, CLOSE, Optional.empty(),
                Set.of(Circumstance.RETREAT_BLOCKED, Circumstance.EXHAUSTED_ARMS));
        assertEquals(Action.HOLD, decide(exhausted, Stance.RETREAT, strikes()).action());
    }

    @Test
    public void unreachableExitCannotBecomeInstantDodgeThroughOverflow() {
        // 无出口用最大时长表示，不能因加躲避余量溢出而被判成瞬间可达。
        var incoming = new Incoming(INCOMING_HIT, 0, HEAVY_DOSE, Set.of(Incoming.Trait.COVERS));
        var blocked = new Repertoire(List.of(), List.of(), timing(0, NO_OWN_HIT, Long.MAX_VALUE));
        assertEquals(Action.ABSORB, decide(encounter(AXE, CLOSE, incoming), Stance.RETREAT, blocked).action());
    }

    @Test
    public void retreatAtSafeDistanceRecoversOrHolds() {
        // 拉开到安全距离后疲劳达门槛就调息，否则原地等待。
        Decision recover = decide(calm(AXE, SAFE), Stance.RETREAT, supports(0, breath(TIRED)));
        assertEquals(Action.RECOVER, recover.action());
        assertEquals(Optional.of(BREATH), recover.technique());
        assertEquals(Action.HOLD, decide(calm(AXE, SAFE), Stance.RETREAT, supports(0, breath(FRESH))).action());
    }

    @Test
    public void idleRecoversOnlyWhenTiredWithQi() {
        // 无威胁时疲劳达门槛且真元够才放恢复式，否则交还原版。
        Fighter drained = new Fighter(1, new Condition(1, 1, DRAINED_QI, 1, 1), Set.of());
        assertEquals(Action.RECOVER, HumanoidTactics.idle(AXE, supports(0, breath(TIRED))).action());
        assertEquals(Action.RELEASE, HumanoidTactics.idle(AXE, supports(0, breath(FRESH))).action());
        assertEquals(Action.RELEASE, HumanoidTactics.idle(drained, supports(0, breath(TIRED))).action());
        assertEquals(Action.RELEASE, HumanoidTactics.idle(AXE, supports(0)).action());
    }

    @Test
    public void readyChecksDistanceOnlyWithThreatAndRefreshWindow() {
        // 无威胁不比距离；有威胁时距离须在区间内；机动式在轻身剩余不超过补放门槛时才就绪。
        assertTrue(HumanoidTactics.ready(breath(TIRED), AXE, OptionalDouble.empty(), 0));
        assertFalse(HumanoidTactics.ready(breath(TIRED), AXE, OptionalDouble.of(CLOSE), 0));
        assertTrue(HumanoidTactics.ready(lightStep(), AXE, OptionalDouble.of(MID), LIGHT_BODY_EXPIRING));
        assertFalse(HumanoidTactics.ready(lightStep(), AXE, OptionalDouble.of(MID), LIGHT_BODY));
    }

    private static Decision decide(Encounter encounter, Stance stance, Repertoire repertoire) {
        // 默认应对阈值。
        return HumanoidTactics.decide(encounter, stance, repertoire, RULES);
    }

    private static Encounter encounter(Fighter self, double distance, Incoming incoming) {
        // 在地面、有来袭打击。
        return new Encounter(self, AXE, distance, Optional.of(incoming), Set.of(Circumstance.ON_GROUND));
    }

    private static Encounter calm(Fighter self, double distance) {
        // 在地面、对方没有在施放。
        return new Encounter(self, AXE, distance, Optional.empty(), Set.of(Circumstance.ON_GROUND));
    }

    private static Repertoire strikes(Strike... strikes) {
        // 只有打击式，能很快走出范围。
        return new Repertoire(List.of(strikes), List.of(), timing(0, NO_OWN_HIT, QUICK_EXIT));
    }

    private static Repertoire supports(long lightBodyMillis, Support... supports) {
        // 叩门在手，另带给定辅助式与轻身剩余。
        return new Repertoire(List.of(KNOCK_STRIKE), List.of(supports),
                timing(lightBodyMillis, NO_OWN_HIT, QUICK_EXIT));
    }

    private static Timing timing(long lightBodyMillis, long ownNextHitMillis, long exitMillis) {
        // 跃步最小距离固定。
        return new Timing(lightBodyMillis, ownNextHitMillis, exitMillis, LEAP_MINIMUM);
    }

    private static Support charge() {
        // 蓄力：接敌前准备，要求近战武器。
        var gate = new Gate(CHARGE_MINIMUM, CHARGE_MAXIMUM, CHARGE_QI, new Fatigue(0, 0),
                Set.of(Requirement.MELEE_WEAPON));
        return new Support(CHARGE, Role.PREPARE, CHARGE_ORDER, gate, 0);
    }

    private static Support lightStep() {
        // 轻步：机动式，轻身将尽时补放。
        var gate = new Gate(0, BREATH_MAXIMUM, 0, new Fatigue(0, 0), Set.of());
        return new Support(LIGHT_STEP, Role.REPOSITION, LIGHT_STEP_ORDER, gate, REFRESH);
    }

    private static Support breath(double fatigue) {
        // 调息：远离威胁、疲劳达门槛时恢复。
        var gate = new Gate(SAFE, BREATH_MAXIMUM, BREATH_QI, new Fatigue(BREATH_FATIGUE, fatigue), Set.of());
        return new Support(BREATH, Role.RECOVER, 0, gate, 0);
    }
}
