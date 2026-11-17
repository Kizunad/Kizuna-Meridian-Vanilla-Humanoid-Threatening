package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.humanoid.server.HumanoidAssessment.Separation;
import dev.kizuna.meridian.humanoid.server.HumanoidAssessment.Stance;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class MeleeGuardAssessmentTest {
    private static final AssessmentRules RULES = HumanoidBrainConfigLoader.defaults().assessment();
    private static final double OVERALL_HP = 0.94;
    private static final double INJURED_PART = 0.2;
    private static final double UNRECOVERED_PART = 0.3;
    private static final double RECOVERED_PART = 0.4;
    private static final double SAFE_DISTANCE = 12;
    private static final double REST_SECONDS = 5;
    private static final double LOW_OVERALL_HP = 0.3;
    private static final double SPENT_ARM = 0.7;
    private static final Separation SAFE = new Separation(SAFE_DISTANCE, 0);

    @Test
    public void weakestPartForcesRetreatWithoutChangingOrdinaryStandGround() {
        Fighter injured = fighter(OVERALL_HP, INJURED_PART, 1);
        assertEquals(Stance.RETREAT, HumanoidAssessment.guarding(injured, Stance.ENGAGE, SAFE, RULES));
        assertEquals(Stance.ENGAGE, HumanoidAssessment.standing(injured, RULES));
        assertEquals(Stance.RETREAT, HumanoidAssessment.guarding(fighter(LOW_OVERALL_HP, 1, 1),
                Stance.ENGAGE, SAFE, RULES));
        assertEquals(Stance.RETREAT, HumanoidAssessment.guarding(fighter(1, 1, SPENT_ARM),
                Stance.ENGAGE, SAFE, RULES));
    }

    @Test
    public void recoveryRequiresWeakestPartAndSeparation() {
        Fighter unhealed = fighter(OVERALL_HP, UNRECOVERED_PART, 1);
        Fighter healed = fighter(OVERALL_HP, RECOVERED_PART, 1);
        assertEquals(Stance.RETREAT, HumanoidAssessment.guarding(unhealed, Stance.RETREAT, SAFE, RULES));
        assertEquals(Stance.ENGAGE, HumanoidAssessment.guarding(healed, Stance.RETREAT, SAFE, RULES));
        assertEquals(Stance.RETREAT, HumanoidAssessment.guarding(healed, Stance.RETREAT,
                new Separation(1, 0), RULES));
        assertEquals(Stance.ENGAGE, HumanoidAssessment.guarding(healed, Stance.RETREAT,
                new Separation(1, REST_SECONDS), RULES));
    }

    private static Fighter fighter(double hp, double part, double arm) {
        var condition = new Fighter.Condition(hp, part, 1, arm, 1);
        return new Fighter(1, condition, Set.of(Fighter.Means.CASTABLE_STRIKE));
    }
}
