package dev.kizuna.meridian.creature.server;

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

/** 生成本批次两档生人的完整人体快照，并在返回前交给 Framework 校验。 */
public final class CreatureBodyGenerator {
    private static final String NONE_ATTRIBUTE = "meridian:none";
    private static final String DEFAULT_TIME_DOMAIN = "meridian_creature:world";
    private static final String DEFAULT_TIME_EPOCH = "initial";

    private final QiVariantRegistry variants;
    private final CreatureConfig config;

    public CreatureBodyGenerator(QiVariantRegistry variants, CreatureConfig config) {
        // 注册表和配置在实例创建时固定，单次生成不会改变外部目录。
        this.variants = Objects.requireNonNull(variants, "variants");
        this.config = Objects.requireNonNull(config, "config");
    }

    public BodyMessages.BodySnapshot generate(CreatureSpecies species, CreatureVariant variant,
                                               UUID entityId, RegionInventory region,
                                               long worldSeed, BodyMessages.TimeCursor settledAt) {
        // 世界种子和实体 UUID 共同决定随机流，读档或重启不会重新抽到另一具身体。
        Objects.requireNonNull(entityId, "entityId");
        return generate(species, variant, entityId, region, randomFor(worldSeed, entityId), settledAt);
    }

    public BodyMessages.BodySnapshot generate(CreatureSpecies species, CreatureVariant variant,
                                               UUID entityId, RegionInventory region,
                                               RandomGenerator random, BodyMessages.TimeCursor settledAt) {
        // 显式变体入口供阶段 1 测试和阶段 2 宿主使用；随机变体通过 rollVariant 单独决定。
        Objects.requireNonNull(species, "species");
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(random, "random");
        BodyMessages.TimeCursor time = settledAt == null ? defaultTime() : settledAt;
        BodyMessages.BodyTemplate template = humanTemplate();
        return buildSnapshot(species, variant, entityId, template, region, random, time);
    }

    public BodyMessages.BodySnapshot generateFromTemplate(CreatureSpecies species, CreatureVariant variant,
                                                            UUID entityId, BodyMessages.BodyTemplate template,
                                                            RegionInventory region, RandomGenerator random,
                                                            BodyMessages.TimeCursor settledAt) {
        // 宿主可以传入已安装注册表中的模板；生成器仍严格限制到 Human 的成人模板。
        Objects.requireNonNull(species, "species");
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(random, "random");
        BodyMessages.TimeCursor time = settledAt == null ? defaultTime() : settledAt;
        return buildSnapshot(species, variant, entityId, template, region, random, time);
    }

    public CreatureVariant rollVariant(CreatureSpecies species, RegionInventory region, RandomGenerator random) {
        // 先把区域浓度映射成有上限的因子，再乘物种基础概率，保持浓度单调。
        Objects.requireNonNull(species, "species");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(random, "random");
        double factor = concentrationFactor(region);
        double probability = config.profile(species).trueQiBaseProbability() * factor;
        return random.nextDouble() < probability ? CreatureVariant.TRUE_QI : CreatureVariant.WANDERING_QI;
    }

    public double trueQiProbability(CreatureSpecies species, RegionInventory region) {
        // 对外提供确定性的概率值，便于区域规则测试验证上限和单调性。
        Objects.requireNonNull(species, "species");
        Objects.requireNonNull(region, "region");
        return config.profile(species).trueQiBaseProbability() * concentrationFactor(region);
    }

    public static RandomGenerator randomFor(long worldSeed, UUID entityId) {
        // 使用两个 UUID 长端和旋转混合，避免只取 hashCode 造成大量碰撞。
        Objects.requireNonNull(entityId, "entityId");
        long seed = worldSeed ^ entityId.getMostSignificantBits()
                ^ Long.rotateLeft(entityId.getLeastSignificantBits(), 29);
        return new java.util.Random(seed);
    }

    private BodyMessages.BodySnapshot buildSnapshot(CreatureSpecies species, CreatureVariant variant,
                                                     UUID entityId, BodyMessages.BodyTemplate template,
                                                     RegionInventory region, RandomGenerator random,
                                                     BodyMessages.TimeCursor time) {
        // 模板先走 Framework 映射校验，再确认 ID 和完整 36 条经脉的批次前置。
        BodyProtobuf.fromMessage(template);
        if (!template.getReference().getId().equals(HumanContent.TEMPLATE_ID)
                || template.getReference().getVersion() != HumanContent.CONTENT_VERSION
                || template.getStructure().getMeridiansCount() != 36) {
            throw CreatureErrors.invalid(CreatureErrors.TEMPLATE);
        }

        CreatureConfig.SpeciesProfile profile = config.profile(species);
        Set<String> opened = selectOpenedMeridians(template.getStructure(), variant, profile, random);
        String mainAttribute = variants.sample(region.eligibleShares(variants), variant.maximumAttributeTier(), random)
                .orElse(NONE_ATTRIBUTE);
        double mainShare = mainAttribute.equals(NONE_ATTRIBUTE)
                ? 1.0 : between(config.mainAttributeShareMinimum(), config.mainAttributeShareMaximum(), random);
        BodyMessages.BodyStructure structure = expandedStructure(template.getStructure(), opened);
        var snapshot = BodyMessages.BodySnapshot.newBuilder().setSchemaVersion(3)
                .setId("meridian_creature:entity/" + entityId)
                .setTemplate(template.getReference()).setStructure(structure)
                .setRealm(BodyMessages.Realm.valueOf(variant.realm().name())).setRevision(0)
                .setSettledAt(time)
                .setVitality(BodyMessages.VitalityState.newBuilder()
                        .setAgeSeconds(between(config.minimumAgeYears(), config.maximumAgeYears(), random)
                                * HumanBirth.SECONDS_PER_YEAR)
                        .setLifespanLimitSeconds(config.lifespanYears() * HumanBirth.SECONDS_PER_YEAR)
                        .setSpentLifespanSeconds(0).setDead(false));

        // 部位、组织和器官从模板完整复制健康状态，只有经脉结构和库存是本批次的出生差异。
        for (BodyMessages.PartDefinition part : structure.getPartsList()) {
            snapshot.addPartStates(BodyMessages.PartState.newBuilder().setId(part.getId()).setHp(part.getMaxHp()));
        }
        for (BodyMessages.TissueDefinition tissue : structure.getTissuesList()) {
            snapshot.addTissueStates(BodyMessages.TissueState.newBuilder().setId(tissue.getId())
                    .setHp(tissue.getMaxHp()).setFatigue(0));
        }
        for (BodyMessages.OrganDefinition organ : structure.getOrgansList()) {
            snapshot.addOrganStates(BodyMessages.OrganState.newBuilder().setId(organ.getId())
                    .setHp(organ.getMaxHp()).setBuffer(BodyMessages.QiInventory.getDefaultInstance())
                    .setFatigueJoules(0));
        }
        for (BodyMessages.MeridianDefinition meridian : structure.getMeridiansList()) {
            boolean isOpened = opened.contains(meridian.getId());
            double lumenFill = isOpened ? between(configFill(variant).lumenMinimum(), configFill(variant).lumenMaximum(), random)
                    : config.naturalFillVolumeRatio() * between(.5, 1, random);
            double wallFill = isOpened ? between(configFill(variant).wallMinimum(), configFill(variant).wallMaximum(), random)
                    : config.naturalFillVolumeRatio() * between(.25, .75, random);
            lumenFill = clampFill(lumenFill * profile.fillMultiplier());
            wallFill = clampFill(wallFill * profile.wallFillMultiplier());
            BodyMessages.QiInventory lumen = inventory(meridian.getLumenVolume() * lumenFill, variant,
                    isOpened, mainAttribute, mainShare, random, true);
            BodyMessages.QiInventory wall = inventory(meridian.getWallVolume() * wallFill, variant,
                    isOpened, mainAttribute, mainShare, random, true);
            snapshot.addMeridianStates(BodyMessages.MeridianState.newBuilder().setId(meridian.getId())
                    .setLumenQi(lumen).setWallQi(wall).setFatigue(0).setCondition(MeridianCondition.INTACT)
                    .setOpening(MeridianOpening.newBuilder()
                            .setStage(isOpened ? MeridianOpeningStage.OPENED : MeridianOpeningStage.UNOPENED)
                            .setProgress(isOpened ? 1 : 0)));
        }

        // 运行快照必须通过同一核心校验；返回消息与已校验值相同，不保留第二份账本。
        BodyMessages.BodySnapshot result = snapshot.build();
        BodyProtobuf.fromMessage(result);
        return result;
    }

    private Set<String> selectOpenedMeridians(BodyMessages.BodyStructure structure, CreatureVariant variant,
                                               CreatureConfig.SpeciesProfile profile, RandomGenerator random) {
        // 复用 Human 的最低境界表，将 8 条游炁层和 8 条真炁层按内容定义分组。
        List<String> wandering = new ArrayList<>();
        List<String> trueQi = new ArrayList<>();
        for (BodyMessages.MeridianDefinition meridian : structure.getMeridiansList()) {
            if (HumanOpening.minimumRealm(meridian.getId()) == BodyMessages.Realm.WANDERING_QI) {
                wandering.add(meridian.getId());
            } else if (HumanOpening.minimumRealm(meridian.getId()) == BodyMessages.Realm.TRUE_QI) {
                trueQi.add(meridian.getId());
            }
        }
        if (wandering.size() != 8 || trueQi.size() != 8) {
            throw CreatureErrors.invalid(CreatureErrors.TEMPLATE + ": 开脉分层不是 8+8");
        }
        var result = new HashSet<String>();
        if (variant == CreatureVariant.TRUE_QI) {
            // 真炁变体必须把完整游炁层作为前置，再随机开启 0–8 条真炁层。
            result.addAll(wandering);
            result.addAll(weightedSelection(trueQi, random.nextInt(0, 9), profile, random));
            return result;
        }
        int rawCount = random.nextInt(1, 9);
        int count = Math.max(1, Math.min(8, (int) Math.round(rawCount * profile.openingCountMultiplier())));
        result.addAll(weightedSelection(wandering, count, profile, random));
        return result;
    }

    private List<String> weightedSelection(List<String> candidates, int count,
                                            CreatureConfig.SpeciesProfile profile, RandomGenerator random) {
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

    private double openingWeight(String id, CreatureConfig.SpeciesProfile profile) {
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

    private BodyMessages.QiInventory inventory(double stableVolume, CreatureVariant variant, boolean opened,
                                                String mainAttribute, double mainShare,
                                                RandomGenerator random, boolean allowResidual) {
        // 先确定占用体积，再按密度反推元数，保证每条经脉的容量校验使用实际体积。
        if (stableVolume <= 0) {
            return BodyMessages.QiInventory.getDefaultInstance();
        }
        CreatureConfig.FillRange fill = configFill(variant);
        double densityMaximum = opened ? fill.densityMaximum()
                : Math.min(fill.densityMaximum(), Math.max(fill.densityMinimum() * 2, Math.nextUp(fill.densityMinimum())));
        double mainDensity = density(fill.densityMinimum(), densityMaximum, random);
        double residualShare = 0;
        boolean useResidual = allowResidual && variant == CreatureVariant.TRUE_QI && opened
                && !mainAttribute.equals(NONE_ATTRIBUTE)
                && random.nextDouble() < fill.freeResidualProbability();
        if (useResidual) {
            residualShare = between(0, fill.freeResidualVolumeMaximum(), random);
        }
        double residualDensity = useResidual ? density(.01, .1, random) : mainDensity;
        double effectiveMainShare = mainShare;
        if (useResidual && residualShare > 0 && mainShare < 1) {
            // 主属性占比按元数计算；若低密度残余会超过体积上限，则提高实际主属性占比。
            double minimumMainShare = mainDensity * (1 - residualShare)
                    / (mainDensity * (1 - residualShare) + residualDensity * residualShare);
            effectiveMainShare = Math.max(mainShare, minimumMainShare);
        }
        // 两种密度不同仍按体积反推总元数；由有效占比保证残余体积不超过配置上限。
        double totalAmount = stableVolume
                / (effectiveMainShare / mainDensity + (1 - effectiveMainShare) / residualDensity);
        double mainAmount = totalAmount * effectiveMainShare;
        double residualAmount = totalAmount * (1 - effectiveMainShare);
        var result = BodyMessages.QiInventory.newBuilder();
        if (mainAmount > 0) {
            result.addEntries(BodyMessages.Qi.newBuilder().setAttribute(mainAttribute).setAmount(mainAmount)
                    .setDensity(mainDensity));
        }
        if (residualAmount > 0) {
            result.addEntries(BodyMessages.Qi.newBuilder().setAttribute(NONE_ATTRIBUTE).setAmount(residualAmount)
                    .setDensity(residualDensity));
        }
        return result.build();
    }

    private CreatureConfig.FillRange configFill(CreatureVariant variant) {
        // 变体到配置区间的映射集中在一个方法，避免内腔和外壁使用不同档位。
        return variant == CreatureVariant.TRUE_QI ? config.trueQiFill() : config.wanderingFill();
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
                .filter(template -> template.getReference().getId().equals(HumanContent.TEMPLATE_ID)
                        && template.getReference().getVersion() == HumanContent.CONTENT_VERSION)
                .findFirst().orElseThrow(() -> CreatureErrors.invalid(CreatureErrors.TEMPLATE));
    }

    private static BodyMessages.TimeCursor defaultTime() {
        // 纯 Java 阶段没有世界时钟宿主，使用固定初始游标；Fabric 阶段会传入真实时钟。
        return BodyMessages.TimeCursor.newBuilder().setDomain(DEFAULT_TIME_DOMAIN).setEpoch(DEFAULT_TIME_EPOCH)
                .setElapsedMillis(0).build();
    }

    private static double between(double minimum, double maximum, RandomGenerator random) {
        // 上界为闭区间配置值；随机生成仍可复现并保持数值有限。
        return minimum == maximum ? minimum : minimum + (maximum - minimum) * random.nextDouble();
    }

    private static double density(double minimum, double maximum, RandomGenerator random) {
        // 密度相态采用左闭右开区间，避免随机尾差跨入下一档。
        double upper = Math.nextDown(maximum);
        return minimum == upper ? minimum : minimum + (upper - minimum) * random.nextDouble();
    }

    private static double clampFill(double value) {
        // 物种乘数只能改变分布，最终占用必须落在稳定容量以内。
        return Math.max(0, Math.min(Math.nextDown(1.0), value));
    }
}
