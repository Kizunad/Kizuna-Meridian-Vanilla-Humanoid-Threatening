package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.human.server.HumanBirth;
import dev.kizuna.meridian.human.server.HumanOpening;
import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.AddonMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.qivariants.server.QiVariantRegistry;
import dev.kizuna.meridian.qivariants.server.QiVariantsContent;
import dev.kizuna.meridian.server.AddonRegistry;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

/** 变体边界、容量和可复现性回归。 */
public class HumanoidBodyGeneratorTest {
    private static final int SEED_COUNT = 24;
    private static final int MERIDIAN_COUNT = 36;
    private static final long WORLD_SEED_MULTIPLIER = 17L;
    private static final long WORLD_SEED_OFFSET = 9;
    private static final long REPRODUCIBLE_WORLD_SEED = 987654321L;
    private static final double EXPECTED_LIFESPAN_YEARS = 80.0;
    private static final double TIME_TOLERANCE = 0.0001;
    private static final double PROBABILITY_TOLERANCE = 0.000001;
    private static final double VOLUME_TOLERANCE = 1e-7;
    private static final double FREE_DENSITY = 0.01;
    private static final double LIQUID_DENSITY = 0.1;
    private HumanoidBodyGenerator generator;
    private HumanoidConfig config;

    @Before
    public void setUp() {
        // 测试使用真实 Human/Qi-Variants 内容包和 Framework 注册表，不伪造模板或属性目录。
        AddonMessages.AddonHostProfile host = AddonMessages.AddonHostProfile.newBuilder()
                .setHostId("meridian:java_reference")
                .setApiVersion(1)
                .addCapabilities(AddonRegistry.BODY_TEMPLATES)
                .addCapabilities(AddonRegistry.ORGAN_RECIPES)
                .addCapabilities(AddonRegistry.ORGAN_INTAKE)
                .addCapabilities(AddonRegistry.ACTIONS)
                .addCapabilities(AddonRegistry.TISSUE_RECOVERY)
                .addCapabilities(AddonRegistry.PASSIVE_QI_FLOW)
                .addCapabilities(AddonRegistry.OPENING_DEFINITIONS)
                .addCapabilities(AddonRegistry.OPENING_RUNTIME)
                .addCapabilities(AddonRegistry.ORGAN_ENDURANCE)
                .build();
        AddonMessages.AddonRegistration registration = AddonMessages.AddonRegistration.newBuilder()
                .setProtocolVersion(1)
                .setHost(host)
                .addBundles(HumanContent.bundle())
                .addBundles(QiVariantsContent.bundle())
                .build();
        AddonRegistry installed = AddonRegistry.load(registration);
        QiVariantRegistry variants = QiVariantRegistry.load(installed, QiVariantsContent.catalog());
        config = HumanoidConfigLoader.defaults();
        generator = new HumanoidBodyGenerator(variants, config);
    }

    @Test
    public void everySpeciesAndVariantStaysWithinTheFrameworkSnapshotContract() {
        // 多个随机种子覆盖全部物种和两档，确保开拓、密度和体积约束没有统计性越界。
        double metalAmount = 2.0;
        RegionInventory region = RegionInventory.fromShares(1.0,
                Map.of("meridian:metal", metalAmount, "meridian:wood", 1.0, "qi_variants:withering", 1.0));
        for (HumanoidSpecies species : HumanoidSpecies.values()) {
            for (HumanoidVariant variant : HumanoidVariant.values()) {
                for (int seed = 0; seed < SEED_COUNT; seed++) {
                    UUID id = UUID.nameUUIDFromBytes((species.entityId() + variant + seed).getBytes());
                    var request = new HumanoidBodyGenerator.GenerationRequest(species, variant, id, region, null);
                    long worldSeed = seed * WORLD_SEED_MULTIPLIER + WORLD_SEED_OFFSET;
                    BodyMessages.BodySnapshot body = generator.generate(request, worldSeed);
                    var vitality = body.getVitality();
                    assertEquals(MERIDIAN_COUNT, body.getMeridianStatesCount());
                    assertEquals(EXPECTED_LIFESPAN_YEARS * HumanBirth.SECONDS_PER_YEAR,
                            vitality.getLifespanLimitSeconds(), TIME_TOLERANCE);
                    double ageSeconds = vitality.getAgeSeconds();
                    assertTrue(ageSeconds >= config.minimumAgeYears() * HumanBirth.SECONDS_PER_YEAR);
                    assertTrue(ageSeconds <= config.maximumAgeYears() * HumanBirth.SECONDS_PER_YEAR);
                    verifyMeridians(body, variant);
                }
            }
        }
    }

    @Test
    public void sameWorldSeedAndUuidProduceTheSameSnapshot() {
        // 生成器必须固定随机调用顺序；相同世界种子和实体身份的快照字节应完全相同。
        UUID id = UUID.fromString("11111111-2222-3333-4444-555555555555");
        double concentration = 1.2;
        double fireAmount = 4.0;
        double earthAmount = 2.0;
        RegionInventory region = RegionInventory.fromShares(concentration,
                Map.of("meridian:fire", fireAmount, "meridian:earth", earthAmount));
        var request = new HumanoidBodyGenerator.GenerationRequest(
                HumanoidSpecies.EVOKER, HumanoidVariant.TRUE_QI, id, region, null);
        byte[] first = generator.generate(request, REPRODUCIBLE_WORLD_SEED).toByteArray();
        byte[] second = generator.generate(request, REPRODUCIBLE_WORLD_SEED).toByteArray();
        assertArrayEquals(first, second);
    }

    @Test
    public void regionInventoryAggregatesPhasesAndFiltersInvalidSources() {
        // 同属性不同相态先合计；无属性和目录外属性不能进入 Qi-Variants 抽取输入。
        double freeMetalAmount = 2;
        double liquidMetalAmount = 3;
        double noAttributeAmount = 20;
        double outsideAmount = 50;
        RegionInventory region = RegionInventory.present(1,
                List.of(new RegionInventory.Entry("meridian:metal", FREE_DENSITY, freeMetalAmount),
                        new RegionInventory.Entry("meridian:metal", LIQUID_DENSITY, liquidMetalAmount),
                        new RegionInventory.Entry("meridian:none", FREE_DENSITY, noAttributeAmount),
                        new RegionInventory.Entry("other:outside", LIQUID_DENSITY, outsideAmount)));
        double expectedMetalAmount = freeMetalAmount + liquidMetalAmount;
        assertEquals(Map.of("meridian:metal", expectedMetalAmount), region.eligibleShares(generatorRegistry()));
    }

    @Test
    public void trueQiProbabilityIsMonotonicAndCapped() {
        // 浓度超过参考值后概率保持封顶，没有区域则使用独立默认因子。
        RegionInventory empty = RegionInventory.present(0, List.of());
        double normalConcentration = 0.5;
        double highConcentration = 2;
        double normalProbability = 0.125;
        double cappedProbability = 0.25;
        RegionInventory normal = RegionInventory.present(normalConcentration, List.of());
        RegionInventory high = RegionInventory.present(highConcentration, List.of());
        assertEquals(0.0, generator.trueQiProbability(HumanoidSpecies.VILLAGER, empty), PROBABILITY_TOLERANCE);
        assertEquals(normalProbability,
                generator.trueQiProbability(HumanoidSpecies.VILLAGER, normal), PROBABILITY_TOLERANCE);
        assertEquals(cappedProbability,
                generator.trueQiProbability(HumanoidSpecies.VILLAGER, high), PROBABILITY_TOLERANCE);
        assertEquals(cappedProbability * config.trueQiNoRegionFactor(),
                generator.trueQiProbability(HumanoidSpecies.VILLAGER, RegionInventory.absent()),
                PROBABILITY_TOLERANCE);
    }

    @Test
    public void malformedConfigurationIsRejected() {
        // 缺失章节不是“使用默认值”，防止运营方误以为配置已生效。
        assertThrows(IllegalArgumentException.class,
                () -> HumanoidConfigLoader.parse("config_version = 1\n"));
    }

    private QiVariantRegistry generatorRegistry() {
        // 从生成器的概率输入无法反向取得注册表；用同一真实内容重新安装供过滤断言使用。
        AddonMessages.AddonHostProfile host = AddonMessages.AddonHostProfile.newBuilder()
                .setHostId("meridian:java_reference")
                .setApiVersion(1)
                .build();
        AddonMessages.AddonRegistration registration = AddonMessages.AddonRegistration.newBuilder()
                .setProtocolVersion(1)
                .setHost(host)
                .addBundles(QiVariantsContent.bundle())
                .build();
        AddonRegistry installed = AddonRegistry.load(registration);
        return QiVariantRegistry.load(installed, QiVariantsContent.catalog());
    }

    private void verifyMeridians(BodyMessages.BodySnapshot body, HumanoidVariant variant) {
        // 每条状态按当前结构容量校验，真炁层级还必须满足游炁前置全开规则。
        var structure = body.getStructure();
        for (int index = 0; index < structure.getMeridiansCount(); index++) {
            BodyMessages.MeridianDefinition definition = structure.getMeridians(index);
            BodyMessages.MeridianState state = body.getMeridianStates(index);
            assertTrue(QiDensity.volume(state.getLumenQi()) <= definition.getLumenVolume() + VOLUME_TOLERANCE);
            assertTrue(QiDensity.volume(state.getWallQi()) <= definition.getWallVolume() + VOLUME_TOLERANCE);
            BodyMessages.Realm minimum = HumanOpening.minimumRealm(definition.getId());
            var openingStage = state.getOpening().getStage();
            if (variant == HumanoidVariant.WANDERING_QI) {
                assertFalse(minimum == BodyMessages.Realm.TRUE_QI
                        && openingStage == BodyMessages.MeridianOpeningStage.OPENED);
            } else if (minimum == BodyMessages.Realm.WANDERING_QI) {
                assertEquals(BodyMessages.MeridianOpeningStage.OPENED, openingStage);
            }
        }
    }
}
