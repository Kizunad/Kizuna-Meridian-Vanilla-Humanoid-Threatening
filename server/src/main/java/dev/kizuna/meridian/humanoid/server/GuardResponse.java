package dev.kizuna.meridian.humanoid.server;

import java.util.Map;

/** 距离以格计，反应、无威胁收势及再抬间隔均以毫秒计。 */
public record GuardResponse(Range range, double angleDegrees, double turnDegreesPerTick, Timing timing,
                            Map<HumanoidTier, Reaction> reactions) {
    public record Range(double meleeEnter, double meleeExit, double ranged) { }
    public record Timing(long recentMeleeMillis, long quietMillis, long restMillis) { }
    public record Reaction(double chance, long delayMillis) { }

    public GuardResponse {
        reactions = Map.copyOf(reactions);
    }
}
