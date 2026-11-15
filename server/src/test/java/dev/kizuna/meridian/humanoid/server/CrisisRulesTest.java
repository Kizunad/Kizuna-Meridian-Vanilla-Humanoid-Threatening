package dev.kizuna.meridian.humanoid.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** 招敌配置：默认文件能读、缺键按默认、拼错的键和越界的值启动时拒绝。 */
public class CrisisRulesTest {
    @Test
    public void defaultsMatchTheDesign() {
        // 设计稿的默认值：最短间隔 10 天、保护 5 天、征兆 3 天两到三次、输了默认中档、逃 64 格。
        CrisisRules rules = CrisisRules.defaults();
        assertTrue(rules.enabled());
        assertEquals("meridian_humanoid:covetous", rules.genericParty());
        assertEquals(10, rules.frequency().minIntervalDays());
        assertEquals(5, rules.frequency().protectionDays());
        assertEquals(3, rules.omen().days());
        assertEquals(2, rules.omen().minCount());
        assertEquals(3, rules.omen().maxCount());
        assertEquals(CrisisRules.LossPenalty.MEDIUM, rules.outcome().lossPenalty());
        assertEquals(64.0, rules.outcome().escapeDistance(), 0);
        assertEquals(List.of(4, 8, 12, 16), rules.heat().rise().meridianNodes());
        assertTrue(CrisisRules.missingDefaultKeys(defaultText()).isEmpty());
    }

    @Test
    public void missingKeysRunOnDefaults() {
        // 旧文件只写了两个键，其余按默认值；缺的键能列出来供启动提示。
        CrisisRules rules = CrisisRules.parse("enabled = false\n[outcome]\nloss_penalty = \"light\"\n");
        assertFalse(rules.enabled());
        assertEquals(CrisisRules.LossPenalty.LIGHT, rules.outcome().lossPenalty());
        assertEquals(50.0, rules.heat().threshold(), 0);
        List<String> missing = CrisisRules.missingDefaultKeys("enabled = false\n");
        assertTrue(missing.contains("outcome.loss_penalty"));
        assertFalse(missing.contains("enabled"));
    }

    @Test
    public void unknownKeysAreRejected() {
        // 拼错的键、把标量写在表的位置上，都报出完整路径。
        assertRejected("[heat]\nthreshhold = 3\n", "heat.threshhold");
        assertRejected("heat = 3\n", "heat");
    }

    @Test
    public void outOfRangeValuesAreRejected() {
        // 代价档（重档暂不能用：经脉伤了没有恢复的规矩）、征兆次数、开脉节点顺序、现身距离、队伍表 ID 都在启动时核对。
        assertRejected("[outcome]\nloss_penalty = \"death\"\n", "loss_penalty");
        assertRejected("[outcome]\nloss_penalty = \"heavy\"\n", "没有恢复");
        assertRejected("[omen]\ncount = [3, 2]\n", "omen.count");
        assertRejected("[heat.rise]\nmeridian_nodes = [8, 4]\n", "meridian_nodes");
        assertRejected("[arrival]\nmin_distance = 50.0\n", "min_distance");
        assertRejected("generic_party = \"covetous\"\n", "generic_party");
        assertRejected("[heat]\nkept_after_win = 1.5\n", "kept_after_win");
        assertRejected("[frequency]\nmin_interval_days = 1.5\n", "min_interval_days");
    }

    private static void assertRejected(String text, String fragment) {
        // 错误信息要指明是哪个键。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> CrisisRules.parse(text));
        assertTrue(failure.getMessage(), failure.getMessage().contains(fragment));
        assertTrue(failure.getMessage(), failure.getMessage().startsWith("招敌配置无效"));
    }

    private static String defaultText() {
        // JAR 里的默认文件本身。
        return new HumanoidToml("test").resource("/meridian-humanoid-crisis.toml");
    }
}
