package dev.kizuna.meridian.humanoid.server;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** 物种的身体死亡规则：按 Human 人体规则裁定，或永不进入死态。 */
public enum HumanoidDeathRule {
    /** 部位总 HP 低于两成或头部归零即死亡，与原版生人相同。 */
    HUMAN,
    /** 部位可以全部归零，身体始终不判死，供训练假人观察累计伤势。 */
    NEVER;

    public String id() {
        // 物种表使用小写名。
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<HumanoidDeathRule> fromId(String id) {
        // 未知名保持为空，由调用方报告配置错误。
        return Arrays.stream(values()).filter(rule -> rule.id().equals(id)).findFirst();
    }
}
