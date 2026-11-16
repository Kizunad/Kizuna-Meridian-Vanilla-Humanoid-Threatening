package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Grapple;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LegStrike;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LimbStrike;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Quantity;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 大脑把哪些式当打击式：不认具体功法包，只看效果。 */
public final class HumanoidRepertoiresTest {
    private static final long HIT_MILLIS = 300;
    private static final double FIST_REACH = 2.5;
    private static final double GRAPPLE_REACH = 1.5;
    private static final double KICK_REACH = 3.5;

    @Test
    public void limbStrikeAndGrappleCountAsStrikesWithTheirOwnReach() {
        // 只有拳掌或只有擒的式也是打击式，首击时刻和够得着的距离取自这一下；它们不是兵器打击，不带打击形状。
        var fist = effect().setLimbStrike(LimbStrike.newBuilder().setDistance(meters(FIST_REACH))).build();
        var grapple = effect().setGrapple(Grapple.newBuilder().setMaximumDistance(meters(GRAPPLE_REACH))).build();
        assertEquals(Optional.of(new HumanoidRepertoires.FirstStrike(HIT_MILLIS, FIST_REACH,
                        HumanoidRepertoires.Kind.CLOSE, Optional.empty())),
                HumanoidRepertoires.firstStrike(definition(fist)));
        assertEquals(Optional.of(new HumanoidRepertoires.FirstStrike(HIT_MILLIS, GRAPPLE_REACH,
                        HumanoidRepertoires.Kind.CLOSE, Optional.empty())),
                HumanoidRepertoires.firstStrike(definition(grapple)));
        // 最早那一下是腿的打击就记成腿法：生物起脚的距离和兵器拳掌分开配。
        var kick = effect().setLegStrike(LegStrike.newBuilder().setDistance(meters(KICK_REACH))).build();
        assertEquals(Optional.of(new HumanoidRepertoires.FirstStrike(HIT_MILLIS, KICK_REACH,
                        HumanoidRepertoires.Kind.KICK, Optional.empty())),
                HumanoidRepertoires.firstStrike(definition(kick)));
    }

    @Test
    public void techniqueThatHitsNobodyIsNotAStrike() {
        // 只有表现的式不打人，不进打击式候选。
        var pose = effect().setPresentation(PresentationEffect.getDefaultInstance()).build();
        assertTrue(HumanoidRepertoires.firstStrike(definition(pose)).isEmpty());
    }

    private static TechniqueEffect.Builder effect() {
        return TechniqueEffect.newBuilder().setAtMillis(HIT_MILLIS);
    }

    private static TechniqueDefinition definition(TechniqueEffect effect) {
        return TechniqueDefinition.newBuilder().addEffects(effect).build();
    }

    private static Quantity meters(double value) {
        return Quantity.newBuilder().setValue(value).setUnit(Unit.METER).build();
    }
}
