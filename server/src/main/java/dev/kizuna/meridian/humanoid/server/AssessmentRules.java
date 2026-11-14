package dev.kizuna.meridian.humanoid.server;

/** 威胁评估阈值：战力权重、对敌余量、硬性撤退门槛与撤退回归门槛。 */
public record AssessmentRules(Weights weights, double castingWeight, double engageMargin, Retreat retreat,
                              Recovery recovery) {
    /** 境界档、可施放打击式、持武器、身体状况和真元占容的权重。 */
    public record Weights(double realm, double strike, double armed, double body, double qi) { }

    /** 完好率、最弱部位比例或较强一臂的有效力量比低于这些值时撤退。 */
    public record Retreat(double hpRatio, double partHpRatio, double armStrengthRatio) { }

    /** 撤退后要恢复到这些比例并且拉开了才重新评估：拉到这一距离，或者连着这么多秒没被对手逼近、也没挨它打。 */
    public record Recovery(double hpRatio, double armStrengthRatio, double distance, double unpressedSeconds) { }
}
