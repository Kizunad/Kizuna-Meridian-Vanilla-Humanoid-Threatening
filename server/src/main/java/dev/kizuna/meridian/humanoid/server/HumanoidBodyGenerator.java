package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.human.server.HumanBirth;
import dev.kizuna.meridian.human.server.HumanOpening;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianCondition;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpening;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpeningStage;
import dev.kizuna.meridian.qivariants.server.QiVariantRegistry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** 生成两档生人的完整人体快照，并在返回前交给 Framework 校验。 */
public final class HumanoidBodyGenerator {
    private static final int SCHEMA_VERSION = 3;
    private static final int MERIDIAN_COUNT = 36;
    private static final int MERIDIANS_PER_REALM = 8;
    private static final int SEED_ROTATION_BITS = 29;
    private static final double UNOPENED_LUMEN_MIN = 0.5;
    private static final double UNOPENED_WALL_MIN = 0.25;
    private static final double UNOPENED_WALL_MAX = 0.75;
    private static final double UNOPENED_DENSITY_MULTIPLIER = 2;
    private static final double RESIDUAL_DENSITY_MIN = 0.01;
    private static final double RESIDUAL_DENSITY_MAX = 0.1;
    private static final String NONE_ATTRIBUTE = "meridian:none";
    private static final String DEFAULT_TIME_DOMAIN = "meridian_humanoid:world";
    private static final String DEFAULT_TIME_EPOCH = "initial";
    private static final MeridianOpening OPENED = MeridianOpening.newBuilder()
            .setStage(MeridianOpeningStage.OPENED)
            .setProgress(1)
            .build();
    private static final MeridianOpening UNOPENED = MeridianOpening.newBuilder()
            .setStage(MeridianOpeningStage.UNOPENED)
            .setProgress(0)
            .build();

    private final QiVariantRegistry variants;
    private final HumanoidConfig config;

    public HumanoidBodyGenerator(QiVariantRegistry variants, HumanoidConfig config) {
        // 注册表和配置在实例创建时固定，单次生成不会改变外部目录。
        this.variants = Objects.requireNonNull(variants, "variants");
        this.config = Objects.requireNonNull(config, "config");
    }

    public BodyMessages.BodySnapshot generate(GenerationRequest request, long worldSeed) {
        // 世界种子和实体 UUID 共同决定随机流，读档或重启不会重新抽到另一具身体。
        UUID entityId = request.entityId();
        Objects.requireNonNull(entityId, "entityId");
        return generate(request, randomFor(worldSeed, entityId));
    }

    public BodyMessages.BodySnapshot generate(GenerationRequest request, RandomGenerator random) {
        // 显式变体与随机流由调用方固定；随机变体通过 rollVariant 单独决定。
        validateSubject(request);
        Objects.requireNonNull(request.region(), "region");
        Objects.requireNonNull(random, "random");
        BodyMessages.TimeCursor time = generationTime(request);
        BodyMessages.BodyTemplate template = humanTemplate();
        return buildSnapshot(request, template, random, time);
    }

    public BodyMessages.BodySnapshot generateFromTemplate(GenerationRequest request,
                                                          BodyMessages.BodyTemplate template,
                                                          RandomGenerator random) {
        // 宿主可以传入已安装注册表中的模板；生成器仍严格限制到 Human 的成人模板。
        validateSubject(request);
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(request.region(), "region");
        Objects.requireNonNull(random, "random");
        BodyMessages.TimeCursor time = generationTime(request);
        return buildSnapshot(request, template, random, time);
    }

    private static void validateSubject(GenerationRequest request) {
        // 先校验实体身份和变体，再读取模板与区域，保持输入错误的定位顺序。
        Objects.requireNonNull(request.species(), "species");
        Objects.requireNonNull(request.variant(), "variant");
        Objects.requireNonNull(request.entityId(), "entityId");
    }

    private static BodyMessages.TimeCursor generationTime(GenerationRequest request) {
        // 未提供世界时钟的纯规则调用使用固定初始游标。
        BodyMessages.TimeCursor settledAt = request.settledAt();
        if (settledAt == null) {
            return defaultTime();
        }
        return settledAt;
    }

    public HumanoidVariant rollVariant(HumanoidSpecies species, RegionInventory region, RandomGenerator random) {
        // 先把区域浓度映射成有上限的因子，再乘物种基础概率，保持浓度单调。
        Objects.requireNonNull(species, "species");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(random, "random");
        double factor = concentrationFactor(region);
        double probability = config.profile(species).trueQiBaseProbability() * factor;
        return random.nextDouble() < probability ? HumanoidVariant.TRUE_QI : HumanoidVariant.WANDERING_QI;
    }

    public double trueQiProbability(HumanoidSpecies species, RegionInventory region) {
        // 对外提供确定性的概率值，便于区域规则测试验证上限和单调性。
        Objects.requireNonNull(species, "species");
        Objects.requireNonNull(region, "region");
        return config.profile(species).trueQiBaseProbability() * concentrationFactor(region);
    }

    public static RandomGenerator randomFor(long worldSeed, UUID entityId) {
        // 使用两个 UUID 长端和旋转混合，避免只取 hashCode 造成大量碰撞。
        Objects.requireNonNull(entityId, "entityId");
        long seed = worldSeed ^ entityId.getMostSignificantBits()
                ^ Long.rotateLeft(entityId.getLeastSignificantBits(), SEED_ROTATION_BITS);
        return new java.util.Random(seed);
    }

    private BodyMessages.BodySnapshot buildSnapshot(GenerationRequest request, BodyMessages.BodyTemplate template,
                                                     RandomGenerator random, BodyMessages.TimeCursor time) {
        // 模板与生成结果都按 Framework 契约校验，随机抽样顺序固定为开脉、属性、年龄、库存。
        validateTemplate(template);
        HumanoidVariant variant = request.variant();
        BodyMessages.BodyStructure original = template.getStructure();
        HumanoidConfig.SpeciesProfile profile = config.profile(request.species());
        Set<String> opened = selectOpenedMeridians(original, variant, profile, random);
        String mainAttribute = variants.sample(request.region().eligibleShares(variants),
                        variant.maximumAttributeTier(), random)
                .orElse(NONE_ATTRIBUTE);
        double mainShare;
        if (mainAttribute.equals(NONE_ATTRIBUTE)) {
            mainShare = 1;
        } else {
            mainShare = between(config.mainAttributeShareMinimum(), config.mainAttributeShareMaximum(), random);
        }
        BodyMessages.BodyStructure structure = expandedStructure(original, opened);
        BodyMessages.VitalityState vitality = vitality(random);
        var snapshot = BodyMessages.BodySnapshot.newBuilder()
                .setSchemaVersion(SCHEMA_VERSION)
                .setId("meridian_humanoid:entity/" + request.entityId())
                .setTemplate(template.getReference())
                .setStructure(structure)
                .setRealm(BodyMessages.Realm.valueOf(variant.realm().name()))
                .setRevision(0)
                .setSettledAt(time)
                .setVitality(vitality);
        addHealthyStates(snapshot, structure);
        var birth = new BirthParameters(variant, profile, opened, mainAttribute, mainShare);
        addMeridianStates(snapshot, birth, random);
        BodyMessages.BodySnapshot result = snapshot.build();
        // 这里只执行快照合法性校验；非法结构、库存或生命状态会抛出协议校验异常。
        BodyProtobuf.fromMessage(result);
        return result;
    }

    private static void validateTemplate(BodyMessages.BodyTemplate template) {
        // 映射会校验模板结构，非法消息抛出协议校验异常，再检查 Human 成人模板身份。
        BodyProtobuf.fromMessage(template);
        var reference = template.getReference();
        if (!reference.getId().equals(HumanContent.TEMPLATE_ID)
                || reference.getVersion() != HumanContent.CONTENT_VERSION
                || template.getStructure().getMeridiansCount() != MERIDIAN_COUNT) {
            throw HumanoidErrors.invalid(HumanoidErrors.TEMPLATE);
        }
    }

    private BodyMessages.VitalityState vitality(RandomGenerator random) {
        // 年龄在配置区间抽样，寿命和死亡标记在出生时固定。
        double ageYears = between(config.minimumAgeYears(), config.maximumAgeYears(), random);
        return BodyMessages.VitalityState.newBuilder()
                .setAgeSeconds(ageYears * HumanBirth.SECONDS_PER_YEAR)
                .setLifespanLimitSeconds(config.lifespanYears() * HumanBirth.SECONDS_PER_YEAR)
                .setSpentLifespanSeconds(0)
                .setDead(false /* deadAtBirth */)
                .build();
    }

    private static void addHealthyStates(BodyMessages.BodySnapshot.Builder snapshot,
                                         BodyMessages.BodyStructure structure) {
        // 按模板顺序复制各类账户的满血状态，保持状态列表与定义列表一一对应。
        addPartStates(snapshot, structure.getPartsList());
        addTissueStates(snapshot, structure.getTissuesList());
        addOrganStates(snapshot, structure.getOrgansList());
    }

    private static void addPartStates(BodyMessages.BodySnapshot.Builder snapshot,
                                      List<BodyMessages.PartDefinition> parts) {
        // 部位出生时使用模板定义的最大生命值。
        for (BodyMessages.PartDefinition part : parts) {
            BodyMessages.PartState state = BodyMessages.PartState.newBuilder()
                    .setId(part.getId())
                    .setHp(part.getMaxHp())
                    .build();
            snapshot.addPartStates(state);
        }
    }

    private static void addTissueStates(BodyMessages.BodySnapshot.Builder snapshot,
                                        List<BodyMessages.TissueDefinition> tissues) {
        // 组织出生时没有疲劳，生命值取模板最大值。
        for (BodyMessages.TissueDefinition tissue : tissues) {
            BodyMessages.TissueState state = BodyMessages.TissueState.newBuilder()
                    .setId(tissue.getId())
                    .setHp(tissue.getMaxHp())
                    .setFatigue(0)
                    .build();
            snapshot.addTissueStates(state);
        }
    }

    private static void addOrganStates(BodyMessages.BodySnapshot.Builder snapshot,
                                       List<BodyMessages.OrganDefinition> organs) {
        // 器官出生时没有缓存库存和疲劳。
        for (BodyMessages.OrganDefinition organ : organs) {
            BodyMessages.OrganState state = BodyMessages.OrganState.newBuilder()
                    .setId(organ.getId())
                    .setHp(organ.getMaxHp())
                    .setBuffer(BodyMessages.QiInventory.getDefaultInstance())
                    .setFatigueJoules(0)
                    .build();
            snapshot.addOrganStates(state);
        }
    }

    private void addMeridianStates(BodyMessages.BodySnapshot.Builder snapshot, BirthParameters birth,
                                    RandomGenerator random) {
        // 同一变体共用装填区间，内腔和外壁沿定义顺序依次抽样。
        HumanoidConfig.FillRange fill = configFill(birth.variant());
        HumanoidConfig.SpeciesProfile profile = birth.profile();
        Set<String> openedIds = birth.opened();
        for (BodyMessages.MeridianDefinition meridian : snapshot.getStructure().getMeridiansList()) {
            String id = meridian.getId();
            boolean opened = openedIds.contains(id);
            MeridianOpening opening = opened ? OPENED : UNOPENED;
            double rawLumenFill;
            double rawWallFill;
            if (opened) {
                rawLumenFill = between(fill.lumenMinimum(), fill.lumenMaximum(), random);
                rawWallFill = between(fill.wallMinimum(), fill.wallMaximum(), random);
            } else {
                double naturalFill = config.naturalFillVolumeRatio();
                rawLumenFill = naturalFill * between(UNOPENED_LUMEN_MIN, 1, random);
                rawWallFill = naturalFill * between(UNOPENED_WALL_MIN, UNOPENED_WALL_MAX, random);
            }
            double lumenFill = clampFill(rawLumenFill * profile.fillMultiplier());
            double wallFill = clampFill(rawWallFill * profile.wallFillMultiplier());
            BodyMessages.QiInventory lumen = inventory(meridian.getLumenVolume() * lumenFill,
                    opening, birth, fill, random);
            BodyMessages.QiInventory wall = inventory(meridian.getWallVolume() * wallFill,
                    opening, birth, fill, random);
            snapshot.addMeridianStates(meridianState(id, lumen, wall, opening));
        }
    }

    private static BodyMessages.MeridianState meridianState(String id, BodyMessages.QiInventory lumen,
                                                            BodyMessages.QiInventory wall,
                                                            MeridianOpening opening) {
        // 经脉出生时结构完整且没有疲劳，开拓事实使用已确定的状态消息。
        return BodyMessages.MeridianState.newBuilder()
                .setId(id)
                .setLumenQi(lumen)
                .setWallQi(wall)
                .setFatigue(0)
                .setCondition(MeridianCondition.INTACT)
                .setOpening(opening)
                .build();
    }

    private Set<String> selectOpenedMeridians(BodyMessages.BodyStructure structure, HumanoidVariant variant,
                                               HumanoidConfig.SpeciesProfile profile, RandomGenerator random) {
        // 复用 Human 的最低境界表，将 8 条游炁层和 8 条真炁层按内容定义分组。
        List<String> wandering = new ArrayList<>();
        List<String> trueQi = new ArrayList<>();
        for (BodyMessages.MeridianDefinition meridian : structure.getMeridiansList()) {
            String id = meridian.getId();
            BodyMessages.Realm minimumRealm = HumanOpening.minimumRealm(id);
            if (minimumRealm == BodyMessages.Realm.WANDERING_QI) {
                wandering.add(id);
            } else if (minimumRealm == BodyMessages.Realm.TRUE_QI) {
                trueQi.add(id);
            }
        }
        if (wandering.size() != MERIDIANS_PER_REALM || trueQi.size() != MERIDIANS_PER_REALM) {
            throw HumanoidErrors.invalid(HumanoidErrors.TEMPLATE + ": 开脉分层不是 8+8");
        }
        var result = new HashSet<String>();
        if (variant == HumanoidVariant.TRUE_QI) {
            // 真炁变体必须把完整游炁层作为前置，再随机开启 0–8 条真炁层。
            result.addAll(wandering);
            result.addAll(weightedSelection(trueQi, random.nextInt(0, MERIDIANS_PER_REALM + 1), profile, random));
            return result;
        }
        int rawCount = random.nextInt(1, MERIDIANS_PER_REALM + 1);
        int count = Math.max(1,
                Math.min(MERIDIANS_PER_REALM, (int) Math.round(rawCount * profile.openingCountMultiplier())));
        result.addAll(weightedSelection(wandering, count, profile, random));
        return result;
    }

    private List<String> weightedSelection(List<String> candidates, int count,
                                            HumanoidConfig.SpeciesProfile profile, RandomGenerator random) {
        // 不放回抽样保留物种对手经/足经的偏向，同时保证每条经脉最多开启一次。
        var remaining = new ArrayList<>(candidates);
        var selected = new ArrayList<String>();
        while (selected.size() < count && !remaining.isEmpty()) {
            double total = remaining.stream().mapToDouble(id -> openingWeight(id, profile)).sum();
            double roll = random.nextDouble() * total;
            String choice = remaining.get(remaining.size() - 1);
            for (String candidate : remaining) {
                roll -= openingWeight(candidate, profile);
                if (roll < 0) {
                    choice = candidate;
                    break;
                }
            }
            selected.add(choice);
            remaining.remove(choice);
        }
        return selected;
    }

    private double openingWeight(String id, HumanoidConfig.SpeciesProfile profile) {
        // 肺/大肠属于手经，胃/脾属于足经；其余候选只在对应层出现。
        if (id.contains("lung") || id.contains("large_intestine")) {
            return profile.armOpeningWeight();
        }
        if (id.contains("stomach") || id.contains("spleen")) {
            return profile.footOpeningWeight();
        }
        return 1;
    }

    private BodyMessages.BodyStructure expandedStructure(BodyMessages.BodyStructure original, Set<String> opened) {
        // 开拓只改变当前稳定内腔体积，外壁、端口和拓扑连接保持 Human 模板事实。
        var structure = original.toBuilder().clearMeridians();
        for (BodyMessages.MeridianDefinition meridian : original.getMeridiansList()) {
            var value = meridian.toBuilder();
            if (opened.contains(meridian.getId())) {
                value.setLumenVolume(meridian.getLumenVolume() + config.openedLumenGain());
            }
            structure.addMeridians(value);
        }
        return structure.build();
    }

    private BodyMessages.QiInventory inventory(double stableVolume, MeridianOpening opening, BirthParameters birth,
                                                HumanoidConfig.FillRange fill, RandomGenerator random) {
        // 先确定占用体积，再按密度反推元数，保证每条经脉的容量校验使用实际体积。
        if (stableVolume <= 0) {
            return BodyMessages.QiInventory.getDefaultInstance();
        }
        double densityMinimum = fill.densityMinimum();
        double configuredMaximum = fill.densityMaximum();
        boolean opened = opening.getStage() == MeridianOpeningStage.OPENED;
        double densityMaximum;
        if (opened) {
            densityMaximum = configuredMaximum;
        } else {
            densityMaximum = Math.min(configuredMaximum,
                    Math.max(densityMinimum * UNOPENED_DENSITY_MULTIPLIER, Math.nextUp(densityMinimum)));
        }
        double mainDensity = density(densityMinimum, densityMaximum, random);
        String mainAttribute = birth.mainAttribute();
        boolean useResidual = birth.variant() == HumanoidVariant.TRUE_QI && opened
                && !mainAttribute.equals(NONE_ATTRIBUTE)
                && random.nextDouble() < fill.freeResidualProbability();
        double residualShare;
        double residualDensity;
        if (useResidual) {
            residualShare = between(0, fill.freeResidualVolumeMaximum(), random);
            residualDensity = density(RESIDUAL_DENSITY_MIN, RESIDUAL_DENSITY_MAX, random);
        } else {
            residualShare = 0;
            residualDensity = mainDensity;
        }
        double effectiveMainShare = effectiveMainShare(birth.mainShare(), mainDensity, residualDensity, residualShare);
        return inventoryForAmounts(stableVolume, mainAttribute, effectiveMainShare, mainDensity, residualDensity);
    }

    private static double effectiveMainShare(double mainShare, double mainDensity,
                                             double residualDensity, double residualShare) {
        // 主属性占比按元数计算；若低密度残余会超过体积上限，则提高实际主属性占比。
        if (residualShare > 0 && mainShare < 1) {
            double mainContribution = mainDensity * (1 - residualShare);
            double residualContribution = residualDensity * residualShare;
            double minimumMainShare = mainContribution / (mainContribution + residualContribution);
            return Math.max(mainShare, minimumMainShare);
        }
        return mainShare;
    }

    private static BodyMessages.QiInventory inventoryForAmounts(double stableVolume, String mainAttribute,
                                                                double mainShare, double mainDensity,
                                                                double residualDensity) {
        // 两种密度不同仍按体积反推总元数；由有效占比保证残余体积不超过配置上限。
        double totalAmount = stableVolume
                / (mainShare / mainDensity + (1 - mainShare) / residualDensity);
        double mainAmount = totalAmount * mainShare;
        double residualAmount = totalAmount * (1 - mainShare);
        var result = BodyMessages.QiInventory.newBuilder();
        if (mainAmount > 0) {
            result.addEntries(qi(mainAttribute, mainAmount, mainDensity));
        }
        if (residualAmount > 0) {
            result.addEntries(qi(NONE_ATTRIBUTE, residualAmount, residualDensity));
        }
        return result.build();
    }

    private static BodyMessages.Qi qi(String attribute, double amount, double density) {
        // 每条库存明细独立构造，属性、元数和密度共同决定实际占用体积。
        return BodyMessages.Qi.newBuilder()
                .setAttribute(attribute)
                .setAmount(amount)
                .setDensity(density)
                .build();
    }

    private HumanoidConfig.FillRange configFill(HumanoidVariant variant) {
        // 变体到配置区间的映射集中在一个方法，避免内腔和外壁使用不同档位。
        if (variant == HumanoidVariant.TRUE_QI) {
            return config.trueQiFill();
        }
        return config.wanderingFill();
    }

    private double concentrationFactor(RegionInventory region) {
        // 没有区域时使用配置默认因子；有区域时按参考浓度线性增长并封顶。
        if (!region.regionPresent()) {
            return config.trueQiNoRegionFactor();
        }
        return Math.min(config.trueQiConcentrationCap(), region.concentration()
                / config.trueQiReferenceConcentration());
    }

    private BodyMessages.BodyTemplate humanTemplate() {
        // Human 内容包是唯一的人体模板来源，生成器不复制或重建人体构型。
        return HumanContent.bundle().getTemplatesList().stream()
                .filter(template -> {
                    var reference = template.getReference();
                    return reference.getId().equals(HumanContent.TEMPLATE_ID)
                            && reference.getVersion() == HumanContent.CONTENT_VERSION;
                })
                .findFirst().orElseThrow(() -> HumanoidErrors.invalid(HumanoidErrors.TEMPLATE));
    }

    private static BodyMessages.TimeCursor defaultTime() {
        // 纯规则调用没有世界时钟宿主时，使用固定初始游标。
        return BodyMessages.TimeCursor.newBuilder()
                .setDomain(DEFAULT_TIME_DOMAIN)
                .setEpoch(DEFAULT_TIME_EPOCH)
                .setElapsedMillis(0)
                .build();
    }

    private static double between(double minimum, double maximum, RandomGenerator random) {
        // 上界为闭区间配置值；随机生成仍可复现并保持数值有限。
        if (minimum == maximum) {
            return minimum;
        }
        return minimum + (maximum - minimum) * random.nextDouble();
    }

    private static double density(double minimum, double maximum, RandomGenerator random) {
        // 密度相态采用左闭右开区间，避免随机尾差跨入下一档。
        double upper = Math.nextDown(maximum);
        if (minimum == upper) {
            return minimum;
        }
        return minimum + (upper - minimum) * random.nextDouble();
    }

    private static double clampFill(double value) {
        // 物种乘数只能改变分布，最终占用必须落在稳定容量以内。
        return Math.max(0, Math.min(Math.nextDown(1.0), value));
    }

    /** 一次生成所需的实体身份、变体、区域与时钟。 */
    public record GenerationRequest(HumanoidSpecies species, HumanoidVariant variant, UUID entityId,
                                    RegionInventory region, BodyMessages.TimeCursor settledAt) {
    }

    private record BirthParameters(HumanoidVariant variant, HumanoidConfig.SpeciesProfile profile,
                                    Set<String> opened, String mainAttribute, double mainShare) {
    }
}
