package dev.kizuna.meridian.humanoid.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.kizuna.meridian.humanoid.server.CrisisHeat.Band;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.Test;

/** 队伍表的形状，以及启动后对队伍表、出身仇家和卷的核对。 */
public class CrisisPartyTest {
    private static final CrisisRules RULES = CrisisRules.defaults();
    private static final String PILLAGER = "minecraft:pillager";

    @Test
    public void everyBandNeedsAStageZeroLineup() {
        // 少了哪一档的第 0 拨都拒绝；三档齐了就行，后面的拨次可有可无。
        List<CrisisParty.Lineup> lineups = new ArrayList<>(complete());
        lineups.add(lineup(Band.TRUE_MANY, 2, 0));
        assertEquals(4, new CrisisParty(lineups).lineups().size());
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> new CrisisParty(List.of(lineup(Band.WANDERING, 0, 1), lineup(Band.TRUE_FEW, 0, 1),
                        lineup(Band.TRUE_MANY, 1, 1))));
        assertTrue(missing.getMessage(), missing.getMessage().contains("true_many"));
    }

    @Test
    public void raidersAreIllagerSideAndEliteNeedsTrueQi() {
        // 村民、行商、凡人都不派；游炁头目不能是精锐。
        assertThrows(IllegalArgumentException.class,
                () -> new CrisisParty.Member("minecraft:villager", HumanoidTier.TRUE_QI, false));
        assertThrows(IllegalArgumentException.class,
                () -> new CrisisParty.Followers("minecraft:wandering_trader", HumanoidTier.WANDERING_QI, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new CrisisParty.Member(PILLAGER, HumanoidTier.MORTAL, false));
        assertThrows(IllegalArgumentException.class,
                () -> new CrisisParty.Member(PILLAGER, HumanoidTier.WANDERING_QI, true));
        assertThrows(IllegalArgumentException.class,
                () -> new CrisisParty.Followers(PILLAGER, HumanoidTier.WANDERING_QI, 3, 2));
    }

    @Test
    public void referencesAreCheckedAfterStart() {
        // 通用队伍表、出身指向的队伍表、foe_volume 都要在；人数超过 max_party 也拒绝；都对就过。
        CrisisParty party = new CrisisParty(complete());
        Map<String, CrisisParty> parties = Map.of(RULES.genericParty(), party, "test:feud", party);
        Map<String, List<CrisisThreat>> origins = Map.of("test:orphan",
                List.of(threat("test:feud", Optional.of("test:lower"))));
        CrisisReferences.require(RULES, parties, origins, "test:lower"::equals);
        assertFails("generic_party", RULES, Map.of("test:feud", party), origins, volume -> true);
        assertFails("test:orphan 第 1 条 crisis 的队伍表不存在", RULES, parties,
                Map.of("test:orphan", List.of(threat("test:missing", Optional.empty()))), volume -> true);
        assertFails("foe_volume 没有装或没有卷轴：test:lower", RULES, parties, origins, volume -> false);
        List<CrisisParty.Lineup> crowded = new ArrayList<>(complete());
        crowded.add(lineup(Band.TRUE_MANY, 1, 4));
        assertFails("超过 max_party", RULES, Map.of(RULES.genericParty(), new CrisisParty(crowded)), Map.of(),
                volume -> true);
    }

    private static void assertFails(String expected, CrisisRules rules, Map<String, CrisisParty> parties,
                                    Map<String, List<CrisisThreat>> origins,
                                    Predicate<String> installed) {
        // 核对失败的错误信息里要说清楚是哪一处。
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> CrisisReferences.require(rules, parties, origins, installed));
        assertTrue(failure.getMessage(), failure.getMessage().contains(expected));
    }

    private static List<CrisisParty.Lineup> complete() {
        // 三档各一种第 0 拨阵容。
        return List.of(lineup(Band.WANDERING, 0, 1), lineup(Band.TRUE_FEW, 0, 1), lineup(Band.TRUE_MANY, 0, 1));
    }

    private static CrisisParty.Lineup lineup(Band band, int stage, int followers) {
        // 真炁掠夺者带头，随从是几个游炁掠夺者。
        return new CrisisParty.Lineup(band, stage, 1, new CrisisParty.Member(PILLAGER, HumanoidTier.TRUE_QI, false),
                List.of(new CrisisParty.Followers(PILLAGER, HumanoidTier.WANDERING_QI, 0, followers)));
    }

    private static CrisisThreat threat(String party, Optional<String> foeVolume) {
        // 计日参数不影响核对。
        CrisisThreat.DayRange days = new CrisisThreat.DayRange(1, 2);
        return new CrisisThreat(party, days, days, 1, HumanoidTier.WANDERING_QI, foeVolume);
    }
}
