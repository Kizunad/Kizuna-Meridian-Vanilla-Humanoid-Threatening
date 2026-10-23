package dev.kizuna.meridian.humanoid.server;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/** 阶段 1 的不可变出生参数；所有数值都可由 TOML 覆盖。 */
public record HumanoidConfig(
        int configVersion,
        double lifespanYears,
        double minimumAgeYears,
        double maximumAgeYears,
        double openedLumenGain,
        double naturalFillVolumeRatio,
        double mainAttributeShareMinimum,
        double mainAttributeShareMaximum,
        double trueQiReferenceConcentration,
        double trueQiConcentrationCap,
        double trueQiNoRegionFactor,
        FillRange wanderingFill,
        FillRange trueQiFill,
        Map<HumanoidSpecies, SpeciesProfile> speciesProfiles,
        boolean particleEnabled,
        int particleIntervalTicks,
        Map<String, Integer> particleColors) {

    public HumanoidConfig {
        // 先校验全局范围，防止生成过程中才发现寿命或体积无法表示。
        if (configVersion <= 0 || !finitePositive(lifespanYears)
                || !finiteRange(minimumAgeYears, maximumAgeYears)
                || maximumAgeYears > lifespanYears || openedLumenGain <= 0
                || !fraction(naturalFillVolumeRatio) || !fraction(mainAttributeShareMinimum)
                || !fraction(mainAttributeShareMaximum) || mainAttributeShareMinimum > mainAttributeShareMaximum
                || !finitePositive(trueQiReferenceConcentration) || !fraction(trueQiConcentrationCap)
                || !fraction(trueQiNoRegionFactor) || particleIntervalTicks <= 0) {
            throw HumanoidErrors.invalid(HumanoidErrors.CONFIG + ": 全局范围无效");
        }
        if (wanderingFill == null || trueQiFill == null) {
            throw HumanoidErrors.invalid(HumanoidErrors.CONFIG + ": 装填范围不能为空");
        }
        if (particleColors == null) {
            throw HumanoidErrors.invalid(HumanoidErrors.CONFIG + ": 粒子颜色不能为空");
        }
        var copiedColors = new HashMap<String, Integer>();
        for (var entry : particleColors.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()
                    || entry.getValue() == null || entry.getValue() < 0 || entry.getValue() > 0xFFFFFF) {
                throw HumanoidErrors.invalid(HumanoidErrors.CONFIG + ": 粒子颜色无效");
            }
            copiedColors.put(entry.getKey(), entry.getValue());
        }
        for (String required : java.util.List.of("meridian:none", "meridian:fire", "meridian:water",
                "meridian:wood", "meridian:metal", "meridian:earth")) {
            if (!copiedColors.containsKey(required)) {
                throw HumanoidErrors.invalid(HumanoidErrors.CONFIG + ": 缺少粒子颜色 " + required);
            }
        }
        particleColors = Map.copyOf(copiedColors);
        var copiedProfiles = new EnumMap<HumanoidSpecies, SpeciesProfile>(HumanoidSpecies.class);
        if (speciesProfiles == null || speciesProfiles.size() != HumanoidSpecies.values().length) {
            throw HumanoidErrors.invalid(HumanoidErrors.CONFIG + ": 必须为七种类人实体提供配置");
        }
        for (HumanoidSpecies species : HumanoidSpecies.values()) {
            SpeciesProfile profile = speciesProfiles.get(species);
            if (profile == null) {
                throw HumanoidErrors.invalid(HumanoidErrors.CONFIG + ": 缺少 " + species.entityId());
            }
            copiedProfiles.put(species, profile);
        }
        speciesProfiles = Map.copyOf(copiedProfiles);
    }

    public SpeciesProfile profile(HumanoidSpecies species) {
        // 物种配置只按枚举读取，未知实体在宿主认领层提前拒绝。
        if (species == null) {
            throw HumanoidErrors.invalid(HumanoidErrors.CONFIG + ": 物种不能为空");
        }
        return speciesProfiles.get(species);
    }

    private static boolean finitePositive(double value) {
        // 正数参数不能接受无穷或 NaN。
        return Double.isFinite(value) && value > 0;
    }

    private static boolean fraction(double value) {
        // 比例统一限定在闭区间，生成器会对实际密度上界采用左闭右开处理。
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }

    private static boolean finiteRange(double minimum, double maximum) {
        // 年龄范围允许固定值，但不能倒置。
        return Double.isFinite(minimum) && Double.isFinite(maximum) && minimum >= 0 && minimum <= maximum;
    }

    /** 一档变体的经脉装填和密度区间。 */
    public record FillRange(double lumenMinimum, double lumenMaximum,
                            double wallMinimum, double wallMaximum,
                            double densityMinimum, double densityMaximum,
                            double freeResidualProbability, double freeResidualVolumeMaximum) {
        public FillRange {
            // 内腔、外壁使用稳定体积比例；密度上限是开区间端点。
            if (!range(lumenMinimum, lumenMaximum) || !range(wallMinimum, wallMaximum)
                    || !Double.isFinite(densityMinimum) || !Double.isFinite(densityMaximum)
                    || densityMinimum < .01 || densityMinimum >= densityMaximum
                    || !fraction(freeResidualProbability) || !fraction(freeResidualVolumeMaximum)) {
                throw HumanoidErrors.invalid(HumanoidErrors.CONFIG + ": 装填或密度范围无效");
            }
        }

        private static boolean range(double minimum, double maximum) {
            // 装填比例可以为零，但不能超过稳定容量。
            return fraction(minimum) && fraction(maximum) && minimum <= maximum;
        }
    }

    /** 单一物种的偏向参数，只影响抽样分布，不改变模板结构。 */
    public record SpeciesProfile(double trueQiBaseProbability,
                                 double openingCountMultiplier,
                                 double fillMultiplier,
                                 double wallFillMultiplier,
                                 double armOpeningWeight,
                                 double footOpeningWeight) {
        public SpeciesProfile {
            // 权重允许为零但不能为负；乘数必须为正，避免生成器静默失去装填。
            if (!fraction(trueQiBaseProbability) || !positive(openingCountMultiplier)
                    || !positive(fillMultiplier) || !positive(wallFillMultiplier)
                    || !nonNegative(armOpeningWeight) || !nonNegative(footOpeningWeight)) {
                throw HumanoidErrors.invalid(HumanoidErrors.CONFIG + ": 物种偏向范围无效");
            }
        }

        private static boolean fraction(double value) {
            // 物种真炁基础概率是闭区间比例。
            return Double.isFinite(value) && value >= 0 && value <= 1;
        }

        private static boolean positive(double value) {
            // 分布乘数必须保留正的抽样机会。
            return Double.isFinite(value) && value > 0;
        }

        private static boolean nonNegative(double value) {
            // 通路偏向可以关闭某类候选，但不能制造负权重。
            return Double.isFinite(value) && value >= 0;
        }
    }
}
