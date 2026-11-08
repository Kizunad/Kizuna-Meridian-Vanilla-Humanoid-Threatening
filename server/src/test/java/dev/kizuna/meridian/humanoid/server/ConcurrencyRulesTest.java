package dev.kizuna.meridian.humanoid.server;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 双通道约束表与显式移动意图的纯规则回归。 */
public final class ConcurrencyRulesTest {
    private static final HumanoidBrainConfig.ConcurrencyRules RULES =
            new HumanoidBrainConfig.ConcurrencyRules(0.5, true);

    @Test
    public void preparationAndStrikeCanMoveButCannotLeap() {
        // 蓄力和打击起手期间允许跟随、保持距离和撤退，跃步意图交给下一次空闲窗口。
        for (HumanoidBrainConfig.ConcurrencyRules.CastPhase phase :
                new HumanoidBrainConfig.ConcurrencyRules.CastPhase[]{
                        HumanoidBrainConfig.ConcurrencyRules.CastPhase.PREPARE,
                        HumanoidBrainConfig.ConcurrencyRules.CastPhase.STRIKE}) {
            assertTrue(RULES.allows(phase, Decision.Movement.FOLLOW));
            assertTrue(RULES.allows(phase, Decision.Movement.HOLD_RANGE));
            assertTrue(RULES.allows(phase, Decision.Movement.RETREAT));
            assertFalse(RULES.allows(phase, Decision.Movement.ASSAULT));
            assertFalse(RULES.allows(phase, Decision.Movement.KITE));
        }
    }

    @Test
    public void recoveryOnlyWalksOrStays() {
        // 调息的策略开关只决定是否沿退路走，不允许误用接近或跃步。
        assertTrue(RULES.allows(HumanoidBrainConfig.ConcurrencyRules.CastPhase.RECOVER,
                Decision.Movement.RETREAT));
        assertTrue(RULES.allows(HumanoidBrainConfig.ConcurrencyRules.CastPhase.RECOVER,
                Decision.Movement.STAY));
        assertFalse(RULES.allows(HumanoidBrainConfig.ConcurrencyRules.CastPhase.RECOVER,
                Decision.Movement.FOLLOW));
        assertFalse(RULES.allows(HumanoidBrainConfig.ConcurrencyRules.CastPhase.RECOVER,
                Decision.Movement.ASSAULT));
    }

    @Test
    public void flyingFreezesBothChannels() {
        // 飞行轨迹接管移动；计划检查器只保留 KEEP，不能在空中另起跃步。
        assertTrue(RULES.allows(HumanoidBrainConfig.ConcurrencyRules.CastPhase.FLYING,
                Decision.Movement.KEEP));
        assertFalse(RULES.allows(HumanoidBrainConfig.ConcurrencyRules.CastPhase.FLYING,
                Decision.Movement.FOLLOW));
        assertFalse(RULES.allows(HumanoidBrainConfig.ConcurrencyRules.CastPhase.FLYING,
                Decision.Movement.ASSAULT));
    }

    @Test
    public void explicitDecisionKeepsTechniqueAndMovementSeparate() {
        // 同一式可以显式携带保持距离，替换移动通道不会改写技法通道。
        Decision original = Decision.cast(Decision.Action.PREPARE, "test:charge",
                Decision.Movement.HOLD_RANGE, "准备");
        Decision replaced = original.withMovement(Decision.Movement.FOLLOW);
        assertEquals(Decision.Action.PREPARE, replaced.action());
        assertEquals(original.technique(), replaced.technique());
        assertEquals(Decision.Movement.FOLLOW, replaced.movement());
    }
}
