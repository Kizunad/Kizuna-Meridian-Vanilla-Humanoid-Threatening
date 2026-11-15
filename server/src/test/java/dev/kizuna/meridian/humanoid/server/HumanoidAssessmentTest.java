package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.humanoid.server.Fighter.Condition;
import dev.kizuna.meridian.humanoid.server.Fighter.Means;
import dev.kizuna.meridian.humanoid.server.HumanoidAssessment.Separation;
import dev.kizuna.meridian.humanoid.server.HumanoidAssessment.Stance;
import org.junit.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;

/** 默认配置下的敌我评估：方案表格中的各组处境与撤退滞回。 */
public class HumanoidAssessmentTest {
    private static final AssessmentRules RULES = HumanoidBrainConfigLoader.defaults().assessment();
    private static final double NEAR = 3;
    private static final double SAFE = 12;
    private static final double JUST_PRESSED = 0;
    private static final double LEFT_ALONE = 5;
    private static final double ALMOST_LEFT_ALONE = 4.9;
    private static final double HEAVY_HP = 0.3;
    private static final double WRECKED_PART = 0.2;
    private static final double EXHAUSTED_ARM = 0.7;
    private static final double WOUNDED_HP = 0.5;
    private static final double TOLERANCE = 1e-9;
    private static final Condition FULL = new Condition(1, 1, 1, 1, 1);
    private static final Set<Means> AXE_ADEPT = EnumSet.of(Means.CASTABLE_STRIKE, Means.VANILLA_OFFENSE,
            Means.MELEE_WEAPON);
    private static final Set<Means> BARE_HANDED = EnumSet.of(Means.VANILLA_OFFENSE);

    @Test
    public void unskilledVillagerRetreatsFromVindicator() {
        // 同为游炁，没有任何进攻手段的一方不比战力，直接撤退。
        Fighter villager = fighter(Realm.WANDERING_QI, FULL, Set.of());
        Fighter vindicator = fighter(Realm.WANDERING_QI, FULL, AXE_ADEPT);
        assertEquals(Stance.RETREAT, stance(villager, vindicator, Stance.ENGAGE));
    }

    @Test
    public void equalVindicatorsEngage() {
        // 同档同手段，战力差为 0，不低于对敌余量。
        Fighter vindicator = fighter(Realm.WANDERING_QI, FULL, AXE_ADEPT);
        assertEquals(Stance.ENGAGE, stance(vindicator, vindicator, Stance.ENGAGE));
    }

    @Test
    public void trueQiAdeptEngagesWanderingVindicator() {
        // 真炁会打击式持斧，比游炁卫道士高一档。
        Fighter adept = fighter(Realm.TRUE_QI, FULL, EnumSet.of(Means.CASTABLE_STRIKE, Means.MELEE_WEAPON));
        Fighter vindicator = fighter(Realm.WANDERING_QI, FULL, AXE_ADEPT);
        assertEquals(Stance.ENGAGE, stance(adept, vindicator, Stance.ENGAGE));
    }

    @Test
    public void higherRealmOpponentForcesRetreat() {
        // 对方高一档，战力差 −1 低于对敌余量 −0.5。
        Fighter wandering = fighter(Realm.WANDERING_QI, FULL, AXE_ADEPT);
        Fighter trueQi = fighter(Realm.TRUE_QI, FULL, AXE_ADEPT);
        assertEquals(Stance.RETREAT, stance(wandering, trueQi, Stance.ENGAGE));
    }

    @Test
    public void unarmedStrikerRetreatsFromArmedPeer() {
        // 同档都会打击式，对方持斧多 0.75，超出对敌余量。
        Fighter unarmed = fighter(Realm.WANDERING_QI, FULL, EnumSet.of(Means.CASTABLE_STRIKE));
        Fighter armed = fighter(Realm.WANDERING_QI, FULL, AXE_ADEPT);
        assertEquals(Stance.RETREAT, stance(unarmed, armed, Stance.ENGAGE));
    }

    @Test
    public void playerFacesAdeptOrUnskilledVillager() {
        // 会叩门的真炁村民持斧会打空手玩家；没有功法的村民遇玩家撤退。
        Fighter player = fighter(Realm.WANDERING_QI, FULL, BARE_HANDED);
        Fighter adept = fighter(Realm.TRUE_QI, FULL, EnumSet.of(Means.CASTABLE_STRIKE, Means.MELEE_WEAPON));
        Fighter unskilled = fighter(Realm.WANDERING_QI, FULL, Set.of());
        assertEquals(Stance.ENGAGE, stance(adept, player, Stance.ENGAGE));
        assertEquals(Stance.RETREAT, stance(unskilled, player, Stance.ENGAGE));
    }

    @Test
    public void hardRetreatIgnoresWeakOpponent() {
        // 完好率、最弱部位或较强一臂任一低于门槛，即使对手是凡人也撤退。
        Fighter mortal = new Fighter(Fighter.MORTAL_TIER, FULL, Set.of());
        var injured = new Condition(HEAVY_HP, 1, 1, 1, 1);
        var wrecked = new Condition(1, WRECKED_PART, 1, 1, 1);
        var exhausted = new Condition(1, 1, 1, EXHAUSTED_ARM, 1);
        for (Condition condition : Set.of(injured, wrecked, exhausted)) {
            Fighter self = fighter(Realm.TRUE_QI, condition, AXE_ADEPT);
            assertEquals(Stance.RETREAT, stance(self, mortal, Stance.ENGAGE));
        }
    }

    @Test
    public void retreatHoldsUntilRecoveredAndClear() {
        // 完好率 0.5 在撤退门槛之上：对敌中继续对敌，撤退中未回到 0.6 不回头；恢复后还要拉开到安全距离。
        Fighter opponent = fighter(Realm.WANDERING_QI, FULL, AXE_ADEPT);
        Fighter wounded = fighter(Realm.TRUE_QI, new Condition(WOUNDED_HP, 1, 1, 1, 1), AXE_ADEPT);
        Fighter healed = fighter(Realm.TRUE_QI, FULL, AXE_ADEPT);
        var far = pressedAt(SAFE);
        var near = pressedAt(NEAR);
        assertEquals(Stance.ENGAGE, stance(wounded, opponent, Stance.ENGAGE));
        assertEquals(Stance.RETREAT, HumanoidAssessment.stance(wounded, opponent, Stance.RETREAT, far, RULES));
        assertEquals(Stance.RETREAT, HumanoidAssessment.stance(healed, opponent, Stance.RETREAT, near, RULES));
        assertEquals(Stance.ENGAGE, HumanoidAssessment.stance(healed, opponent, Stance.RETREAT, far, RULES));
    }

    @Test
    public void retreatEndsWhenLeftAloneShortOfSafeDistance() {
        // 屋里退不到安全距离：对手连着 5 秒没逼近、也没打到我，就算拉开了，重新比战力；差一点不算。
        Fighter opponent = fighter(Realm.WANDERING_QI, FULL, AXE_ADEPT);
        Fighter healed = fighter(Realm.TRUE_QI, FULL, AXE_ADEPT);
        var leftAlone = new Separation(NEAR, LEFT_ALONE);
        var almost = new Separation(NEAR, ALMOST_LEFT_ALONE);
        assertEquals(Stance.ENGAGE, HumanoidAssessment.stance(healed, opponent, Stance.RETREAT, leftAlone, RULES));
        assertEquals(Stance.RETREAT, HumanoidAssessment.stance(healed, opponent, Stance.RETREAT, almost, RULES));
    }

    @Test
    public void beingLeftAloneDoesNotOverrideInjuryOrOdds() {
        // 没被逼只顶替「拉开到安全距离」这一条：伤没养回来的不回头；战力差不够的重新比完还是撤退。
        Fighter opponent = fighter(Realm.WANDERING_QI, FULL, AXE_ADEPT);
        Fighter wounded = fighter(Realm.TRUE_QI, new Condition(WOUNDED_HP, 1, 1, 1, 1), AXE_ADEPT);
        Fighter stronger = fighter(Realm.TRUE_QI, FULL, AXE_ADEPT);
        Fighter weaker = fighter(Realm.WANDERING_QI, FULL, AXE_ADEPT);
        var leftAlone = new Separation(NEAR, LEFT_ALONE);
        assertEquals(Stance.RETREAT, HumanoidAssessment.stance(wounded, opponent, Stance.RETREAT, leftAlone, RULES));
        assertEquals(Stance.RETREAT, HumanoidAssessment.stance(weaker, stronger, Stance.RETREAT, leftAlone, RULES));
    }

    @Test
    public void castingAddsWeight() {
        // 正在施放的一方战力多出施放权重。
        Fighter idle = fighter(Realm.WANDERING_QI, FULL, AXE_ADEPT);
        var castingMeans = EnumSet.copyOf(AXE_ADEPT);
        castingMeans.add(Means.CASTING);
        Fighter casting = fighter(Realm.WANDERING_QI, FULL, castingMeans);
        double gain = HumanoidAssessment.power(casting, RULES) - HumanoidAssessment.power(idle, RULES);
        assertEquals(RULES.castingWeight(), gain, TOLERANCE);
    }

    private static Stance stance(Fighter self, Fighter opponent, Stance previous) {
        // 近身处境，距离不影响对敌中的判断。
        return HumanoidAssessment.stance(self, opponent, previous, pressedAt(NEAR), RULES);
    }

    private static Separation pressedAt(double distance) {
        // 对手刚逼近过或刚打到我，只能靠距离算拉开。
        return new Separation(distance, JUST_PRESSED);
    }

    @Test
    public void standingGroundOnlyYieldsToSpentArmsOrNoMeans() {
        // 有「不退」职守：打不过、重伤、部位打烂都照样对敌；两臂虚脱或没有进攻手段才撤。
        Set<Means> strike = Set.of(Means.CASTABLE_STRIKE);
        var strong = fighter(Realm.TRUE_QI, FULL, Set.of(Means.CASTABLE_STRIKE, Means.MELEE_WEAPON));
        var weak = fighter(Realm.WANDERING_QI, FULL, strike);
        assertEquals(Stance.RETREAT, HumanoidAssessment.stance(weak, strong, Stance.ENGAGE, pressedAt(NEAR), RULES));
        assertEquals(Stance.ENGAGE, HumanoidAssessment.standing(weak, RULES));
        var injured = fighter(Realm.WANDERING_QI, new Condition(HEAVY_HP, WRECKED_PART, 1, 1, 1), strike);
        assertEquals(Stance.ENGAGE, HumanoidAssessment.standing(injured, RULES));
        var exhausted = fighter(Realm.WANDERING_QI, new Condition(1, 1, 1, EXHAUSTED_ARM, 1), strike);
        assertEquals(Stance.RETREAT, HumanoidAssessment.standing(exhausted, RULES));
        assertEquals(Stance.RETREAT, HumanoidAssessment.standing(fighter(Realm.TRUE_QI, FULL, Set.of()), RULES));
    }

    private static Fighter fighter(Realm realm, Condition condition, Set<Means> means) {
        // 有身体的一方按境界取档。
        return new Fighter(Fighter.tier(realm), condition, means);
    }
}
