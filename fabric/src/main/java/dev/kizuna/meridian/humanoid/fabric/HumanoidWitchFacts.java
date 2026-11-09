package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.brewing.fabric.BrewQueries;
import dev.kizuna.meridian.brewing.fabric.BrewResidues;
import dev.kizuna.meridian.brewing.server.BrewBodyFacts;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.humanoid.server.WitchRules;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BrewPartGroup;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Category;
import dev.kizuna.meridian.technique.server.TechniqueState;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;

/** 女巫即时观察；通用环境和身体事实不保存到大脑的长期状态。 */
final class HumanoidWitchFacts {
    private static final TagKey<Item> HEAVY_ARMOR = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("meridian_humanoid", "witch_heavy_armor"));
    record Limbs(double arms, double legs, double combined, boolean allHurt) {
        BrewPartGroup worst() {
            // 合计比例更低的一组优先，平局取两臂。
            return arms <= legs ? BrewPartGroup.ARMS : BrewPartGroup.LEGS;
        }
    }

    record Environment(boolean water, boolean rain, boolean fireResistance, boolean melee, boolean heavyArmor) { }
    record Mind(double power, long charmImmunityMillis, boolean charmed, boolean recovering) { }
    record Target(LivingEntity entity, Environment environment, Mind mind,
                  Map<BrewKind, Double> residues, BrewPartGroup armorGroup) { }

    private HumanoidWitchFacts() {
        // 只建立即时只读事实，不复制药效计时或残药存储。
    }

    static Limbs limbs(BodySnapshot body) {
        // 四肢比例与敷药组复用 Brewing 查询，四肢都伤仍逐部位检查。
        var ratios = BrewQueries.limbRatios(body);
        boolean allHurt = true;
        for (var part : body.getStructure().getPartsList()) {
            String id = part.getId();
            boolean arm = BrewQueries.matchesPart(id, BrewPartGroup.ARMS);
            boolean leg = BrewQueries.matchesPart(id, BrewPartGroup.LEGS);
            if (!arm && !leg) {
                continue;
            }
            double hp = body.getPartStatesList().stream().filter(state -> state.getId().equals(id))
                    .findFirst().orElseThrow().getHp();
            allHurt &= hp < part.getMaxHp();
        }
        return new Limbs(ratios.arms(), ratios.legs(), ratios.combined(), allHurt);
    }

    static Target target(Witch witch, LivingEntity target, WitchRules rules) {
        // 残药按药种取最强部位，避免部位多就提前达到催发阈值。
        var residues = new EnumMap<BrewKind, Double>(BrewKind.class);
        for (var residue : BrewResidues.onTarget(witch.getUUID(), target.getUUID())) {
            residues.merge(residue.key().kind(), residue.dose(), Math::max);
        }
        var environment = new Environment(target.isInWater(), target.level().isRainingAt(target.blockPosition()),
                target.hasEffect(MobEffects.FIRE_RESISTANCE),
                target.getMainHandItem().has(DataComponents.WEAPON),
                heavyArmor(target) >= rules.environment().heavyArmorDefense());
        return new Target(target, environment, mind(target), Map.copyOf(residues), armorGroup(target));
    }

    private static Mind mind(LivingEntity target) {
        // 功法类别读唯一身体进度，脑力使用 Brewing 与药效一致的公式。
        double power = Double.POSITIVE_INFINITY;
        boolean recovering = false;
        var observed = TechniqueFabric.service().observe(target.getUUID());
        if (observed.isPresent()) {
            var body = observed.orElseThrow().body();
            power = BrewBodyFacts.mentalPower(BodyProtobuf.toMessage(body));
            var progress = TechniqueState.read(body);
            if (progress.hasActiveCast()) {
                recovering = TechniqueFabric.service().registry()
                        .find(progress.getActiveCast().getRequest().getTechnique())
                        .filter(definition -> definition.getCategory() == Category.RECOVERY).isPresent();
            }
        }
        return new Mind(power, BrewQueries.charmImmunityMillis(target), BrewQueries.charmed(target), recovering);
    }

    static List<LivingEntity> enemies(Witch witch, double radius) {
        // 只数已有敌意的存活目标；同一袭击中的同伴和创造玩家不算敌人。
        return witch.level().getEntitiesOfClass(LivingEntity.class, witch.getBoundingBox().inflate(radius),
                entity -> hostile(witch, entity) && witch.distanceToSqr(entity) <= radius * radius);
    }

    static boolean hostile(Witch witch, LivingEntity entity) {
        // 原版目标、攻击者和大脑记忆共用敌意判定；受惑保护也必须保留。
        return entity != witch && entity.isAlive() && !entity.isSpectator() && !ally(witch, entity)
                && (!(entity instanceof Player player) || !player.isCreative())
                && !HumanoidCharmController.protects(witch, entity)
                && HumanoidThreats.enemy(witch, entity);
    }

    static boolean ally(Witch witch, LivingEntity entity) {
        // 原版女巫不默认属于灾厄友方标签；同一活动袭击中的 Raider 仍是明确的治疗同伴。
        if (entity == null) {
            return false;
        }
        return witch.isAlliedTo(entity) || entity instanceof Raider raider && witch.hasActiveRaid()
                && raider.getCurrentRaid() == witch.getCurrentRaid();
    }

    static boolean insects(Witch witch, WitchRules rules) {
        // 洞穴光照、夜间或附近虫群提供招虫场景，水雨由目标事实单独排除。
        if (witch.level().getMaxLocalRawBrightness(witch.blockPosition()) <= rules.environment().darkLightCeiling()) {
            return true;
        }
        double radius = rules.environment().insectRadius();
        return !witch.level().getEntitiesOfClass(Mob.class, witch.getBoundingBox().inflate(radius),
                entity -> entity.isAlive() && (entity instanceof Spider || entity instanceof Silverfish)
                        && witch.distanceToSqr(entity) <= radius * radius).isEmpty();
    }

    private static BrewPartGroup armorGroup(LivingEntity target) {
        // 按各段实际护甲值求最多覆盖段，胸甲覆盖胸臂，护腿与靴共同覆盖双腿。
        var armor = new EnumMap<BrewPartGroup, Double>(BrewPartGroup.class);
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            var group = BrewPartGroup.LEGS;
            if (slot == EquipmentSlot.HEAD) {
                group = BrewPartGroup.HEAD_NECK;
            } else if (slot == EquipmentSlot.CHEST) {
                group = BrewPartGroup.TORSO;
            }
            var stack = target.getItemBySlot(slot);
            double defense = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
                    .compute(Attributes.ARMOR, 0, slot);
            armor.merge(group, defense, Double::sum);
        }
        return armor.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                .orElse(BrewPartGroup.TORSO);
    }

    private static double heavyArmor(LivingEntity target) {
        // 重甲材质由物品标签扩展；皮甲的总护甲值高也不会冒充铁钻石甲。
        double defense = 0;
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            var stack = target.getItemBySlot(slot);
            if (stack.is(HEAVY_ARMOR)) {
                defense += stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
                        .compute(Attributes.ARMOR, 0, slot);
            }
        }
        return defense;
    }
}
