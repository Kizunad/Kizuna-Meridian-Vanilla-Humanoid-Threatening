package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.protocol.HealthProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageResolution;
import dev.kizuna.meridian.protocol.pb.HealthMessages.HpLoss;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 物种登记：物种表读取、默认值、拒绝规则、内置物种不变，以及永不死亡的伤害裁定。 */
public class HumanoidSpeciesRegistrationTest {
    private static final String PROFILE = """
            [species."%s".profile]
            true_qi_base_probability = 0.0
            opening_count_multiplier = 1.0
            fill_multiplier = 1.0
            wall_fill_multiplier = 1.0
            arm_opening_weight = 1.0
            foot_opening_weight = 1.0
            """;
    private static final String DUMMY_ENTRY = """
            [species."%s"]
            template = "human:adult"
            default_tier = "mortal"
            death_rule = "never"
            brain = false
            """;
    private static final String PLAIN_ENTRY = """
            [species."%s"]
            template = "human:adult"
            default_tier = "true_qi"
            """;
    private static final int BUILT_IN_COUNT = 7;
    private static final double LOSS_HP = 3;

    @Test
    public void tableRegistersSpeciesWithItsOwnDefinition() {
        // 登记后按实体 ID 认领，定义里的档位、死亡规则与大脑开关原样可读，出生画像取自定义而非出生配置。
        String id = "registration_test:dummy";
        HumanoidSpecies species = HumanoidSpeciesDefinitions.registerAll(entry(DUMMY_ENTRY, id)).get(0);
        HumanoidSpeciesDefinition definition = species.definition().orElseThrow();
        assertSame(species, HumanoidSpecies.fromEntityId(id).orElseThrow());
        assertEquals(id, species.name());
        assertEquals(HumanoidTier.MORTAL, definition.defaultTier());
        assertEquals(HumanoidDeathRule.NEVER, definition.deathRule());
        assertFalse(species.brainManaged());
        assertTrue(HumanoidSpecies.registered().contains(species));
        assertEquals(definition.profile(), HumanoidConfigLoader.defaults().profile(species));
    }

    @Test
    public void omittedControlsDefaultToHumanDeathAndManagedBrain() {
        // 不写 death_rule 与 brain 时按人体规则判死并由大脑接管。
        String id = "registration_test:plain";
        HumanoidSpecies species = HumanoidSpeciesDefinitions.registerAll(entry(PLAIN_ENTRY, id)).get(0);
        assertEquals(HumanoidDeathRule.HUMAN, species.definition().orElseThrow().deathRule());
        assertEquals(HumanoidTier.TRUE_QI, species.definition().orElseThrow().defaultTier());
        assertTrue(species.brainManaged());
    }

    @Test
    public void builtInSpeciesStayFixed() {
        // 内置七种没有登记定义、都由大脑接管，values() 不含登记物种。
        assertEquals(BUILT_IN_COUNT, HumanoidSpecies.values().size());
        for (HumanoidSpecies species : HumanoidSpecies.values()) {
            assertTrue(species.definition().isEmpty());
            assertTrue(species.brainManaged());
            assertSame(species, HumanoidSpecies.fromEntityId(species.entityId()).orElseThrow());
        }
        assertEquals("VINDICATOR", HumanoidSpecies.VINDICATOR.name());
        assertTrue(HumanoidSpecies.fromEntityId("minecraft:zombie").isEmpty());
    }

    @Test
    public void invalidTablesAreRejectedWithoutPartialRegistration() {
        // 档位、模板、字段、brain 类型、命名空间或实体 ID 冲突任一不合法都拒绝；整张表不会部分登记。
        String fresh = "registration_test:never_registered";
        String taken = "registration_test:taken";
        HumanoidSpeciesDefinitions.registerAll(entry(PLAIN_ENTRY, taken));
        var invalid = Map.of(
                "未知档位", entry(PLAIN_ENTRY, fresh).replace("\"true_qi\"", "\"immortal\""),
                "非人体模板", entry(PLAIN_ENTRY, fresh).replace("human:adult", "human:child"),
                "未知字段", entry(PLAIN_ENTRY, fresh).replace("template", "unknown = 1\ntemplate"),
                "brain 非布尔", entry(DUMMY_ENTRY, fresh).replace("brain = false", "brain = \"off\""),
                "无命名空间", entry(PLAIN_ENTRY, "dummy"),
                "覆盖内置", entry(PLAIN_ENTRY, HumanoidSpecies.VILLAGER.entityId()),
                "重复登记", entry(PLAIN_ENTRY, fresh) + entry(PLAIN_ENTRY, taken));
        invalid.forEach((label, text) -> {
            var failure = assertThrows(label, IllegalArgumentException.class,
                    () -> HumanoidSpeciesDefinitions.registerAll(text));
            assertTrue(label, failure.getMessage().startsWith(HumanoidErrors.SPECIES));
        });
        assertTrue(HumanoidSpecies.fromEntityId(fresh).isEmpty());
    }

    @Test
    public void neverRuleKeepsLossesButDropsLethal() {
        // 永不死亡只改致死结论，各目标损失原样保留。
        HealthTarget head = HealthTarget.newBuilder()
                .setPartId("head")
                .build();
        HpLoss loss = HpLoss.newBuilder()
                .setTarget(head)
                .setHp(LOSS_HP)
                .build();
        DamageResolution lethal = DamageResolution.newBuilder()
                .setLethal(true /* lethal */)
                .addLosses(loss)
                .build();
        byte[] kept = HumanoidDamagePolicy.survive(HealthProtobuf.encodeResolution(lethal));
        DamageResolution decided = HealthProtobuf.decodeResolution(kept);
        assertFalse(decided.getLethal());
        assertEquals(List.of(loss), decided.getLossesList());
    }

    private static String entry(String entry, String entityId) {
        // 物种条目加上同名画像子表。
        return entry.formatted(entityId) + PROFILE.formatted(entityId);
    }
}
