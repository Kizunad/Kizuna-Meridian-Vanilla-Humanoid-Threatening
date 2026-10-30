package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.humanoid.server.Fighter.Condition;
import dev.kizuna.meridian.humanoid.server.Fighter.Means;
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
        assertEquals(Stance.ENGAGE, HumanoidAssessment.stance(wounded, opponent, Stance.ENGAGE, NEAR, RULES));
        assertEquals(Stance.RETREAT, HumanoidAssessment.stance(wounded, opponent, Stance.RETREAT, SAFE, RULES));
        assertEquals(Stance.RETREAT, HumanoidAssessment.stance(healed, opponent, Stance.RETREAT, NEAR, RULES));
        assertEquals(Stance.ENGAGE, HumanoidAssessment.stance(healed, opponent, Stance.RETREAT, SAFE, RULES));
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
        return HumanoidAssessment.stance(self, opponent, previous, NEAR, RULES);
    }

    private static Fighter fighter(Realm realm, Condition condition, Set<Means> means) {
        // 有身体的一方按境界取档。
        return new Fighter(Fighter.tier(realm), condition, means);
    }
}
