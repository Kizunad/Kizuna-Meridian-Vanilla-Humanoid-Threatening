package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.human.server.HumanContent;
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

/** 阶段 1 变体边界、容量和可复现性回归。 */
public class HumanoidBodyGeneratorTest {
    private HumanoidBodyGenerator generator;
    private HumanoidConfig config;

    @Before
    public void setUp() {
        // 测试使用真实 Human/Qi-Variants 内容包和 Framework 注册表，不伪造模板或属性目录。
        AddonMessages.AddonHostProfile host = AddonMessages.AddonHostProfile.newBuilder()
                .setHostId("meridian:java_reference").setApiVersion(1)
                .addCapabilities(AddonRegistry.BODY_TEMPLATES)
                .addCapabilities(AddonRegistry.ORGAN_RECIPES)
                .addCapabilities(AddonRegistry.ORGAN_INTAKE)
                .addCapabilities(AddonRegistry.ACTIONS)
                .addCapabilities(AddonRegistry.TISSUE_RECOVERY)
                .addCapabilities(AddonRegistry.PASSIVE_QI_FLOW)
                .addCapabilities(AddonRegistry.OPENING_DEFINITIONS)
                .addCapabilities(AddonRegistry.OPENING_RUNTIME)
                .addCapabilities(AddonRegistry.ORGAN_ENDURANCE).build();
        AddonMessages.AddonRegistration registration = AddonMessages.AddonRegistration.newBuilder()
                .setProtocolVersion(1).setHost(host)
                .addBundles(HumanContent.bundle()).addBundles(QiVariantsContent.bundle()).build();
        AddonRegistry installed = AddonRegistry.load(registration);
        QiVariantRegistry variants = QiVariantRegistry.load(installed, QiVariantsContent.catalog());
        config = HumanoidConfigLoader.defaults();
        generator = new HumanoidBodyGenerator(variants, config);
    }

    @Test
    public void everySpeciesAndVariantStaysWithinTheFrameworkSnapshotContract() {
        // 多个随机种子覆盖全部物种和两档，确保开拓、密度和体积约束没有统计性越界。
        RegionInventory region = RegionInventory.fromShares(1.0,
                Map.of("meridian:metal", 2.0, "meridian:wood", 1.0,
                        "qi_variants:withering", 1.0));
        for (HumanoidSpecies species : HumanoidSpecies.values()) {
            for (HumanoidVariant variant : HumanoidVariant.values()) {
                for (int seed = 0; seed < 24; seed++) {
                    UUID id = UUID.nameUUIDFromBytes((species.entityId() + variant + seed).getBytes());
                    BodyMessages.BodySnapshot body = generator.generate(species, variant, id, region,
                            seed * 17L + 9, null);
                    assertEquals(36, body.getMeridianStatesCount());
                    assertEquals(80.0 * 365.25 * 24 * 60 * 60,
                            body.getVitality().getLifespanLimitSeconds(), 0.0001);
                    assertTrue(body.getVitality().getAgeSeconds()
                            >= config.minimumAgeYears() * 365.25 * 24 * 60 * 60);
                    assertTrue(body.getVitality().getAgeSeconds()
                            <= config.maximumAgeYears() * 365.25 * 24 * 60 * 60);
                    verifyMeridians(body, variant);
                }
            }
        }
    }

    @Test
    public void sameWorldSeedAndUuidProduceTheSameSnapshot() {
        // 生成器必须固定随机调用顺序；相同世界种子和实体身份的快照字节应完全相同。
        UUID id = UUID.fromString("11111111-2222-3333-4444-555555555555");
        RegionInventory region = RegionInventory.fromShares(1.2,
                Map.of("meridian:fire", 4.0, "meridian:earth", 2.0));
        byte[] first = generator.generate(HumanoidSpecies.EVOKER, HumanoidVariant.TRUE_QI, id, region,
                987654321L, null).toByteArray();
        byte[] second = generator.generate(HumanoidSpecies.EVOKER, HumanoidVariant.TRUE_QI, id, region,
                987654321L, null).toByteArray();
        assertArrayEquals(first, second);
    }

    @Test
    public void regionInventoryAggregatesPhasesAndFiltersInvalidSources() {
        // 同属性不同相态先合计；无属性和目录外属性不能进入 Qi-Variants 抽取输入。
        RegionInventory region = RegionInventory.present(1,
                List.of(new RegionInventory.Entry("meridian:metal", .01, 2),
                        new RegionInventory.Entry("meridian:metal", .1, 3),
                        new RegionInventory.Entry("meridian:none", .01, 20),
                        new RegionInventory.Entry("other:outside", .1, 50)));
        assertEquals(Map.of("meridian:metal", 5.0), region.eligibleShares(generatorRegistry()));
    }

    @Test
    public void trueQiProbabilityIsMonotonicAndCapped() {
        // 浓度超过参考值后概率保持封顶，没有区域则使用独立默认因子。
        RegionInventory empty = RegionInventory.present(0, List.of());
        RegionInventory normal = RegionInventory.present(.5, List.of());
        RegionInventory high = RegionInventory.present(2, List.of());
        assertEquals(0.0, generator.trueQiProbability(HumanoidSpecies.VILLAGER, empty), 0.000001);
        assertEquals(.125, generator.trueQiProbability(HumanoidSpecies.VILLAGER, normal), 0.000001);
        assertEquals(.25, generator.trueQiProbability(HumanoidSpecies.VILLAGER, high), 0.000001);
        assertEquals(.25 * config.trueQiNoRegionFactor(),
                generator.trueQiProbability(HumanoidSpecies.VILLAGER, RegionInventory.absent()), 0.000001);
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
                .setHostId("meridian:java_reference").setApiVersion(1).build();
        AddonRegistry installed = AddonRegistry.load(AddonMessages.AddonRegistration.newBuilder()
                .setProtocolVersion(1).setHost(host).addBundles(QiVariantsContent.bundle()).build());
        return QiVariantRegistry.load(installed, QiVariantsContent.catalog());
    }

    private void verifyMeridians(BodyMessages.BodySnapshot body, HumanoidVariant variant) {
        // 每条状态按当前结构容量校验，真炁层级还必须满足游炁前置全开规则。
        for (int index = 0; index < body.getStructure().getMeridiansCount(); index++) {
            BodyMessages.MeridianDefinition definition = body.getStructure().getMeridians(index);
            BodyMessages.MeridianState state = body.getMeridianStates(index);
            assertTrue(QiDensity.volume(state.getLumenQi()) <= definition.getLumenVolume() + 1e-7);
            assertTrue(QiDensity.volume(state.getWallQi()) <= definition.getWallVolume() + 1e-7);
            BodyMessages.Realm minimum = HumanOpening.minimumRealm(definition.getId());
            if (variant == HumanoidVariant.WANDERING_QI) {
                assertFalse(minimum == BodyMessages.Realm.TRUE_QI
                        && state.getOpening().getStage() == BodyMessages.MeridianOpeningStage.OPENED);
            } else if (minimum == BodyMessages.Realm.WANDERING_QI) {
                assertEquals(BodyMessages.MeridianOpeningStage.OPENED, state.getOpening().getStage());
            }
        }
    }
}
