package dev.kizuna.meridian.humanoid.server;

/** 战术阈值：来袭应对的时间与剂量门槛，以及撤退要拉开到的安全距离。 */
public record TacticRules(Reaction reaction, double safeDistance) {
    /**
     * 来袭在反应窗口内才考虑躲或抗；打断要求首击比对方起手结束早出余量；
     * 自己的式剩余不超过门槛或预计剂量比低于门槛时硬抗；走出范围要比命中早出躲避余量。
     */
    public record Reaction(long windowMillis, long interruptMarginMillis, long absorbOwnHitMillis,
                           double absorbDoseRatio, long dodgeMarginMillis) { }
}
