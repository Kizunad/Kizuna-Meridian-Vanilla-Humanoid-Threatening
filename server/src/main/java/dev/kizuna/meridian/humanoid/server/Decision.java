package dev.kizuna.meridian.humanoid.server;

import java.util.Optional;

/** 一次战术决策：技法意图、独立移动意图和一句中文理由。 */
public record Decision(Action action, Optional<String> technique, Movement movement, String reason) {
    /** 大脑能给出的全部动作；落到原版控制上的方式由各接入适配器决定。 */
    public enum Action {
        HOLD, INTERRUPT, DODGE, ABSORB, ASSAULT, KITE, PREPARE, RECOVER, STRIKE, APPROACH, RETREAT, RELEASE
    }

    /** 技法与移动同时决定时使用的移动通道；KEEP 表示保留上一个移动计划。 */
    public enum Movement {
        KEEP, VANILLA, FOLLOW, HOLD_RANGE, STAY, RETREAT, ASSAULT, KITE, DODGE
    }

    public Decision {
        // 决策记录必须明确包含两条通道，避免执行层从动作名称猜测移动副作用。
        if (action == null || technique == null || movement == null || reason == null) {
            throw new IllegalArgumentException("决策通道不能为空");
        }
    }

    public Decision(Action action, Optional<String> technique, String reason) {
        // 保留旧的三参数入口，让既有纯战术调用按动作默认移动通道继续工作。
        this(action, technique, defaultMovement(action), reason);
    }

    public static Decision of(Action action, String reason) {
        // 不施放具体式的动作，同时使用动作对应的默认移动意图。
        return new Decision(action, Optional.empty(), defaultMovement(action), reason);
    }

    public static Decision cast(Action action, String technique, String reason) {
        // 指定施放某一式的动作，同时使用动作对应的默认移动意图。
        return new Decision(action, Optional.of(technique), defaultMovement(action), reason);
    }

    public static Decision cast(Action action, String technique, Movement movement, String reason) {
        // 指定施放式与移动方式，供蓄力期间的两种策略共用同一技法意图。
        return new Decision(action, Optional.of(technique), movement, reason);
    }

    public Decision withMovement(Movement next) {
        // 只替换移动通道，保留日志动作、式和理由。
        return new Decision(action, technique, next, reason);
    }

    private static Movement defaultMovement(Action action) {
        // 旧动作的默认移动只用于兼容既有战术测试，新的并行路径会显式覆盖它。
        return switch (action) {
            case HOLD -> Movement.KEEP;
            case INTERRUPT, STRIKE, APPROACH -> Movement.FOLLOW;
            case DODGE -> Movement.DODGE;
            case ABSORB, PREPARE, RECOVER -> Movement.STAY;
            case ASSAULT -> Movement.ASSAULT;
            case KITE -> Movement.KITE;
            case RETREAT -> Movement.RETREAT;
            case RELEASE -> Movement.VANILLA;
        };
    }
}
