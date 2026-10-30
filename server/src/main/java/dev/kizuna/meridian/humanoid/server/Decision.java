package dev.kizuna.meridian.humanoid.server;

import java.util.Optional;

/** 一次战术决策：动作、需要施放的式和一句中文理由。 */
public record Decision(Action action, Optional<String> technique, String reason) {
    /** 大脑能给出的全部动作；落到原版控制上的方式由各接入适配器决定。 */
    public enum Action {
        HOLD, INTERRUPT, DODGE, ABSORB, ASSAULT, KITE, PREPARE, RECOVER, STRIKE, APPROACH, RETREAT, RELEASE
    }

    public static Decision of(Action action, String reason) {
        // 不施放具体式的动作。
        return new Decision(action, Optional.empty(), reason);
    }

    public static Decision cast(Action action, String technique, String reason) {
        // 指定施放某一式的动作。
        return new Decision(action, Optional.of(technique), reason);
    }
}
