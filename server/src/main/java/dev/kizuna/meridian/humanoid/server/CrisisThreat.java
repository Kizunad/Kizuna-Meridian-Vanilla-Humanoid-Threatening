package dev.kizuna.meridian.humanoid.server;

import java.util.Objects;
import java.util.Optional;

/**
 * 出身给招敌引擎的一条仇家参数（威胁类型 {@code meridian_humanoid:crisis}）。
 * 这里只管形状和范围；队伍表和卷在不在，由服务器启动后的核对管。
 *
 * @param party 队伍表 ID
 * @param timing 首拨与赢、逃、输后下一拨的日期范围
 * @param stages 一共几拨，0 表示没有了断
 * @param minRealm 玩家到这一境才开始计日，只能是游炁或真炁
 * @param foeVolume 最后一拨（stages 为 0 时每一拨）头目必会、必掉的卷
 */
public record CrisisThreat(String party, Timing timing, int stages, HumanoidTier minRealm, Optional<String> foeVolume) {
    public record Timing(DayRange firstAfterDays, DayRange intervalDays, Optional<DayRange> setbackDays) {
        public Timing {
            Objects.requireNonNull(firstAfterDays, "first_after_days");
            Objects.requireNonNull(intervalDays, "interval_days");
            Objects.requireNonNull(setbackDays, "setback_days");
        }
    }

    public CrisisThreat(String party, DayRange firstAfterDays, DayRange intervalDays, int stages,
                       HumanoidTier minRealm, Optional<String> foeVolume) {
        this(party, new Timing(firstAfterDays, intervalDays, Optional.empty()), stages, minRealm, foeVolume);
    }

    public DayRange firstAfterDays() {
        return timing.firstAfterDays();
    }

    public DayRange intervalDays() {
        return timing.intervalDays();
    }

    public CrisisThreat {
        // 字段不对直接拒绝，出身框架把错误连同出身 ID 一起报出来。
        requireId(party, "party");
        Objects.requireNonNull(timing, "timing");
        Objects.requireNonNull(minRealm, "min_realm");
        Objects.requireNonNull(foeVolume, "foe_volume");
        if (stages < 0) {
            throw new IllegalArgumentException("stages 不能是负数：" + stages);
        }
        if (minRealm == HumanoidTier.MORTAL) {
            throw new IllegalArgumentException("min_realm 只能是 wandering_qi 或 true_qi");
        }
        foeVolume.ifPresent(volume -> requireId(volume, "foe_volume"));
    }

    static void requireId(String id, String field) {
        // 数据包里的 ID 都要带命名空间；出身框架会在前面加上是哪个出身。
        if (!CrisisRules.namespaced(id)) {
            throw new IllegalArgumentException(field + " 必须是命名空间 ID：" + id);
        }
    }

    public boolean startsAt(boolean trueQi) {
        // 游炁一选定就开始计日；要真炁的等玩家突破。
        return minRealm == HumanoidTier.WANDERING_QI || trueQi;
    }

    /** 两个整数的天数区间，最早和最晚，含两头。 */
    public record DayRange(int min, int max) {
        // 构造时核对。
        public DayRange {
            // 天数不能为负，最早不能晚于最晚。
            if (min < 0 || max < min) {
                throw new IllegalArgumentException("天数区间要满足 0 ≤ 最早 ≤ 最晚：[" + min + ", " + max + "]");
            }
        }
    }
}
