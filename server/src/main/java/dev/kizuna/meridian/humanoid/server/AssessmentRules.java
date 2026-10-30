package dev.kizuna.meridian.humanoid.server;

/** 威胁评估阈值：战力权重、对敌余量、硬性撤退门槛与撤退回归门槛。 */
public record AssessmentRules(Weights weights, double castingWeight, double engageMargin, Retreat retreat,
                              Recovery recovery) {
    /** 境界档、可施放打击式、持武器、身体状况和真元占容的权重。 */
    public record Weights(double realm, double strike, double armed, double body, double qi) { }

    /** 完好率、最弱部位比例或较强一臂的有效力量比低于这些值时撤退。 */
    public record Retreat(double hpRatio, double partHpRatio, double armStrengthRatio) { }

    /** 撤退后要同时恢复到这些比例并拉开到这一距离才重新评估。 */
    public record Recovery(double hpRatio, double armStrengthRatio, double distance) { }
}
