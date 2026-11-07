package dev.kizuna.meridian.humanoid.server;

import java.util.Optional;
import java.util.Set;

/** 一次决策看到的交战处境：双方战力、水平距离、对方来袭打击和自身运动状态。 */
public record Encounter(Fighter self, Fighter opponent, double distance, Optional<Incoming> incoming,
                        Set<Circumstance> circumstances) {
    /** 自身运动与受击状态。 */
    public enum Circumstance {
        LEAPING, ON_GROUND, UNDER_ATTACK, RETREAT_BLOCKED, DEFENSIVE_STRIKE, EXHAUSTED_ARMS
    }

    /** 对方正在施放的式对我的预报；毫秒都从本次决策起算，剂量比为预计剂量占我最弱存活部位当前 HP。 */
    public record Incoming(long millisUntilHit, long millisUntilWarmupEnd, double doseRatio, Set<Trait> traits) {
        /** 打击范围覆盖我；对方仍在可被僵直打断的起手中。 */
        public enum Trait { COVERS, INTERRUPTIBLE }

        public Incoming {
            // 特征集合只读。
            traits = Set.copyOf(traits);
        }

        public boolean has(Trait trait) {
            // 按名字查询单项特征。
            return traits.contains(trait);
        }
    }

    public Encounter {
        // 处境集合只读。
        circumstances = Set.copyOf(circumstances);
    }

    public boolean has(Circumstance circumstance) {
        // 按名字查询单项处境。
        return circumstances.contains(circumstance);
    }
}
