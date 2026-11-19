package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.humanoid.server.HumanoidBodyGenerator;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.humanoid.server.SpeciesBodyRules;
import dev.kizuna.meridian.protocol.pb.AddonMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageResolution;
import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.minecraft.world.entity.Mob;

/** 只有带测试标签的个体才接管；模板、出生和判死都由测试模组登记。 */
public final class SpeciesHookFixture implements ModInitializer {
    static final String TEMPLATE = "species_test:body";
    static final String CLAIM = "species_hook_test";
    private static final long WOUND_DURATION_MILLIS = 60000;
    private static final double WOUND_TEMPO = 0.5;
    static final String TABLE = """
            [species."%1$s"]
            template = "species_test:body"
            default_tier = "mortal"
            [species."%1$s".profile]
            true_qi_base_probability = 0.0
            opening_count_multiplier = 1.0
            fill_multiplier = 1.0
            wall_fill_multiplier = 1.0
            arm_opening_weight = 1.0
            foot_opening_weight = 1.0
            """;

    @Override
    public void onInitialize() {
        var rules = new SpeciesBodyRules(SpeciesHookFixture::body, SpeciesHookFixture::lethal);
        HumanoidSpeciesHooks.registerAll(TABLE.formatted("minecraft:goat"), rules,
                entity -> entity.entityTags().contains(CLAIM), List.of(bundle()));
        // 命令生成的炽足兽也使用未开脉的工厂，可以直接核对请求档与出生档。
        HumanoidSpeciesHooks.registerAll(TABLE.formatted("minecraft:strider"), rules, entity -> true, List.of());
        var invalid = new SpeciesBodyRules(SpeciesHookFixture::malformed, null);
        HumanoidSpeciesHooks.registerAll(TABLE.formatted("minecraft:mooshroom"), invalid,
                entity -> entity.entityTags().contains(CLAIM), List.of());
    }

    static AddonMessages.AddonBundle bundle() {
        var human = HumanContent.bundle();
        var range = AddonMessages.ContentVersionRange.newBuilder()
                .setMinimum(HumanContent.CONTENT_VERSION)
                .setMaximum(HumanContent.CONTENT_VERSION)
                .build();
        var dependency = AddonMessages.AddonDependency.newBuilder()
                .setNamespace(HumanContent.NAMESPACE)
                .setVersions(range)
                .build();
        var manifest = human.getManifest().toBuilder()
                .setNamespace("species_test")
                .addDependencies(dependency)
                .build();
        var adult = human.getTemplates(0);
        var reference = adult.getReference().toBuilder().setId(TEMPLATE).build();
        var template = adult.toBuilder().setReference(reference).build();
        return AddonMessages.AddonBundle.newBuilder()
                .setSchemaVersion(human.getSchemaVersion())
                .setManifest(manifest)
                .addTemplates(template)
                .build();
    }

    private static BodySnapshot body(SpeciesBodyRules.Birth birth) {
        var request = new HumanoidBodyGenerator.GenerationRequest(HumanoidSpecies.VILLAGER, birth.tier().variant(),
                birth.entityId(), birth.region().inventory(), birth.now());
        var base = HumanoidFabric.generator().generateUnopened(request, birth.random());
        var reference = base.getTemplate().toBuilder().setId(TEMPLATE).build();
        return base.toBuilder().setTemplate(reference).build();
    }

    static HumanoidBodyState wounded(Mob mob) {
        HumanoidSpeciesBirths.birth(mob, HumanoidTier.MORTAL);
        var access = (HumanoidEntityAccess) mob;
        var born = access.meridian$humanoidBody();
        var snapshot = born.snapshot();
        var start = snapshot.getSettledAt();
        long startMillis = start.getElapsedMillis();
        var result = snapshot.toBuilder();
        // 在两臂和一腿留同源的肌肉伤与恒定 tempo 修正，覆盖左右利手。
        var affected = List.of("right_upper_arm", "left_upper_arm", "right_thigh");
        for (String part : affected) {
            var tissue = snapshot.getStructure().getTissuesList().stream()
                    .filter(t -> t.getPartId().equals(part) && t.getKind().equals("human:muscle"))
                    .findFirst().orElseThrow();
            String id = tissue.getId();
            var injured = result.getTissueStatesBuilderList().stream().filter(t -> t.getId().equals(id))
                    .findFirst().orElseThrow();
            injured.setHp(injured.getHp() - 1);
            result.addModifiers(slowMuscle(id, startMillis));
        }
        var saved = new HumanoidBodyState.SavedBody(mob.getUUID(), result.build().toByteArray(),
                born.encodeReceipts(), born.regionSource());
        var species = HumanoidSpeciesHooks.claimed(mob).orElseThrow();
        var state = HumanoidBodyState.restore(mob.getUUID(), saved, start, species);
        access.meridian$humanoidBody(state);
        return state;
    }

    private static BodyMessages.CapabilityModifier slowMuscle(String tissue, long startMillis) {
        var start = BodyMessages.CurvePoint.newBuilder().setValue(1).build();
        var end = start.toBuilder().setOffsetMillis(WOUND_DURATION_MILLIS).build();
        return BodyMessages.CapabilityModifier.newBuilder()
                .setId("species_test:wound/" + tissue)
                .setSource("species_test:wound")
                .setTissueId(tissue)
                .setStrengthPeak(1)
                .setBurstPeak(1)
                .setEndurancePeak(1)
                .setRecoveryPeak(1)
                .setTempoPeak(WOUND_TEMPO)
                .setStartMillis(startMillis)
                .addCurve(start)
                .addCurve(end)
                .build();
    }

    private static BodySnapshot malformed(SpeciesBodyRules.Birth birth) {
        var base = body(birth);
        if (birth.tier() == HumanoidTier.MORTAL) {
            return base.toBuilder().setId("invalid").build();
        }
        return base.toBuilder().clearSettledAt().build();
    }

    private static boolean lethal(BodySnapshot wounded, DamageResolution human) {
        // 只有左脚归零才致死，以同一具身体检验两种相反的裁决。
        return wounded.getPartStatesList().stream()
                .anyMatch(part -> part.getId().equals("left_foot") && part.getHp() == 0);
    }
}
