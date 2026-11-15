package dev.kizuna.meridian.humanoid.server;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 服务器启动后核对一次：通用队伍表、每条出身仇家指向的队伍表和卷都在，每张队伍表的人数不超过上限。
 * 那时队伍表、出身和功法注册表才齐；不对就启动失败，不带着排不出人的仇家跑。
 */
public final class CrisisReferences {
    private CrisisReferences() {
        // 纯核对，不持有状态。
    }

    /**
     * 不对就抛出，错误里带是哪张队伍表或哪个出身的第几条。
     *
     * @param rules 招敌配置
     * @param parties 已加载的队伍表，按 ID
     * @param threatsByOrigin 每个出身的 crisis 参数，按出身 ID，顺序和出身里写的一样
     * @param volumeInstalled 某一卷是否已装、有卷轴（打赢最后一拨要掉它）
     */
    public static void require(CrisisRules rules, Map<String, CrisisParty> parties,
                               Map<String, List<CrisisThreat>> threatsByOrigin, Predicate<String> volumeInstalled) {
        // 先看队伍表自身，再看谁指向它们。
        parties.forEach((id, party) -> {
            try {
                party.requireFits(rules.strength().maxParty());
            } catch (IllegalArgumentException invalid) {
                throw fail("队伍表 " + id + "：" + invalid.getMessage());
            }
        });
        if (!parties.containsKey(rules.genericParty())) {
            throw fail("generic_party 指向的队伍表不存在：" + rules.genericParty());
        }
        threatsByOrigin.forEach((origin, threats) -> {
            for (int index = 0; index < threats.size(); index++) {
                requireThreat(origin + " 第 " + (index + 1) + " 条 crisis", threats.get(index), parties,
                        volumeInstalled);
            }
        });
    }

    private static void requireThreat(String where, CrisisThreat threat, Map<String, CrisisParty> parties,
                                      Predicate<String> volumeInstalled) {
        // 队伍表要在；写了 foe_volume 的话那一卷要已装、有卷轴。
        if (!parties.containsKey(threat.party())) {
            throw fail("出身 " + where + " 的队伍表不存在：" + threat.party());
        }
        threat.foeVolume().filter(volume -> !volumeInstalled.test(volume)).ifPresent(volume -> {
            throw fail("出身 " + where + " 的 foe_volume 没有装或没有卷轴：" + volume);
        });
    }

    private static IllegalStateException fail(String message) {
        // 统一前缀，和配置错误一样好找。
        return new IllegalStateException(HumanoidErrors.CRISIS + "：" + message);
    }
}
