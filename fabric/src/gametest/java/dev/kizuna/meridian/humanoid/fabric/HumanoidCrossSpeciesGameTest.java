package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.inventory.MobInventories;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStatusBenefits;
import java.util.LinkedHashSet;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.illager.Evoker;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 外种实体通过正式大脑使用外来功法，并保留原版物品生命周期。 */
public final class HumanoidCrossSpeciesGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_humanoid_cross_species_test");
    private static final int TIMEOUT = 240;
    private static final int OBSERVE_TICKS = 180;
    private static final int ORIGIN = 2;
    private static final int DOOR_Z = ORIGIN + 1;
    private static final int DOOR_FLOOR_X = 6;
    private static final int DOOR_FLOOR_Z = 36;
    private static final int EVOKER_FLOOR_X = 18;
    private static final int EVOKER_FLOOR_Z = 5;
    private static final int EVOKER_TARGET_X = 15;
    private static final double DOOR_TARGET_DISTANCE = 2.0;
    private static final double TARGET_HEALTH = 1000;
    private static final String DOOR_VOLUME = "meridian_door_breaker:upper";
    private static final String DOOR_TECHNIQUE = "meridian_door_breaker:cleave";
    private static final String LEG_TECHNIQUE = "meridian_peddler:snap_kick";
    private static final String FIST_TECHNIQUE = "meridian_villager:crushing_fist";
    private static final String EARTH_TECHNIQUE = "meridian_evoker:earth_legs";
    private static final String EARTH = "meridian:earth";

    @GameTest(maxTicks = TIMEOUT, structure = HumanoidWayfarerTestSupport.STRUCTURE)
    public void pillagerSwapsAxeHitsAndBreaksWoodenDoor(GameTestHelper context) {
        LOGGER.info("GameTest humanoid_cross_species_game_test_pillager_swaps_axe_hits_and_breaks_wooden_door");
        floor(context, DOOR_FLOOR_X, DOOR_FLOOR_Z);
        Pillager pillager = pillager(context, DOOR_VOLUME, Items.CROSSBOW);
        Cow target = target(context, pillager, DOOR_TARGET_DISTANCE);
        pillager.setNoAi(true /* aiDisabled */);
        pillager.setTarget(null);
        pillager.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        placeDoor(context);
        Set<String> castIds = new LinkedHashSet<>();
        Set<String> activeHands = new LinkedHashSet<>();
        boolean[] axeObserved = {false};
        context.runAfterDelay(10, () -> {
            pillager.setNoAi(false /* aiDisabled */);
            pillager.setTarget(target);
            poll(context, pillager, target, OBSERVE_TICKS, () -> {
                if (pillager.getTarget() != target) {
                    pillager.setTarget(target);
                }
                String castId = castId(pillager, DOOR_TECHNIQUE);
                if (!castId.isEmpty()) {
                    castIds.add(castId);
                }
                TechniqueProgress progress = progress(pillager);
                if (progress.hasActiveCast()) {
                    String activeTechnique = progress.getActiveCast().getRequest().getTechnique().getId();
                    String mainHand = pillager.getMainHandItem().getItem().toString();
                    activeHands.add(activeTechnique + "=" + mainHand);
                    if (activeTechnique.equals(DOOR_TECHNIQUE)
                            && pillager.getMainHandItem().is(Items.IRON_AXE)) {
                        axeObserved[0] = true;
                    }
                }
                boolean doorBroken = !context.getLevel().getBlockState(door(context)).is(Blocks.OAK_DOOR);
                boolean hit = target.getHealth() < TARGET_HEALTH;
                boolean finished = !HumanoidTechniqueHost.isCasting(pillager);
                return doorBroken && hit && finished;
            }, () -> {
                context.assertTrue(hasCast(pillager, DOOR_TECHNIQUE), "掠夺者没有施放破门斩");
                boolean doorBroken = !context.getLevel().getBlockState(door(context)).is(Blocks.OAK_DOOR);
                context.assertTrue(doorBroken, "木门没有被破坏");
                context.assertTrue(target.getHealth() < TARGET_HEALTH, "破门斩没有命中目标");
                context.assertTrue(axeObserved[0], "破门斩施放期间主手没有换出铁斧：" + activeHands);
                context.assertTrue(pillager.getMainHandItem().is(Items.CROSSBOW), "收势后没有还回原版弩");
                context.assertTrue(hasPackItem(pillager, Items.IRON_AXE), "收势后背囊没有保留铁斧");
                LOGGER.info("跨物种破门 caster={} technique={} castIds={} damage={} doorBroken={} hand={} pack={}",
                        pillager.getStringUUID(), DOOR_TECHNIQUE, castIds, TARGET_HEALTH - target.getHealth(),
                        !context.getLevel().getBlockState(door(context)).is(Blocks.OAK_DOOR),
                        pillager.getMainHandItem(), MobInventories.inventory(pillager).snapshot());
                cleanup(context, pillager, target);
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT, structure = HumanoidWayfarerTestSupport.STRUCTURE)
    public void witchUsesLegTechniqueDuringPotionAiIdleWindow(GameTestHelper context) {
        LOGGER.info("GameTest humanoid_cross_species_game_test_witch_uses_leg_technique_during_potion_ai_idle_window");
        Witch witch = WitchTestSupport.witch(context, HumanoidTier.TRUE_QI);
        HumanoidWayfarerTestSupport.clearKnownTechniques(witch);
        TechniqueFabric.service().learn(witch.getUUID(), LEG_TECHNIQUE);
        HumanoidTechniqueHost.prepare(witch);
        VillagerTarget target = VillagerTarget.create(context, witch, 2.2);
        witch.setNoAi(false /* aiDisabled */);
        witch.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        witch.setPersistenceRequired();
        Set<String> castIds = new LinkedHashSet<>();
        context.runAfterDelay(1, () -> {
            witch.setTarget(target.entity());
            refreshHost(witch);
            HumanoidWitchAi.decide(witch);
            poll(context, witch, target.entity(), OBSERVE_TICKS, () -> {
                String castId = castId(witch, LEG_TECHNIQUE);
                if (!castId.isEmpty()) {
                    castIds.add(castId);
                }
                if (HumanoidTechniqueHost.hasActiveCast(witch)) {
                    witch.setNoAi(true /* aiDisabled */);
                }
                if (target.entity().getHealth() >= TARGET_HEALTH && !HumanoidTechniqueHost.hasActiveCast(witch)) {
                    refreshHost(witch);
                    HumanoidWitchAi.decide(witch);
                }
                return target.entity().getHealth() < TARGET_HEALTH;
            },
                    () -> {
                        context.assertTrue(hasCast(witch, LEG_TECHNIQUE), "女巫空档没有施放腿法");
                        boolean damaged = target.entity().getHealth() < TARGET_HEALTH;
                        context.assertTrue(damaged, "女巫腿法没有造成真实伤害");
                        LOGGER.info("跨物种女巫腿法 caster={} technique={} castIds={} damage={}",
                                witch.getStringUUID(), LEG_TECHNIQUE, castIds,
                                TARGET_HEALTH - target.entity().getHealth());
                        cleanup(context, witch, target.entity());
                    });
        });
    }

    private static void refreshHost(Witch witch) {
        if (TechniqueFabric.service().statusHost(witch.getUUID()).isEmpty()) {
            HumanoidTechniqueHost.prepare(witch);
        }
    }

    @GameTest(maxTicks = TIMEOUT, structure = HumanoidWayfarerTestSupport.STRUCTURE)
    public void pillagerUsesSleeveFistWithoutChangingCrossbow(GameTestHelper context) {
        LOGGER.info("GameTest humanoid_cross_species_game_test_pillager_uses_sleeve_fist_without_changing_crossbow");
        HumanoidWayfarerTestSupport.floor(context);
        Pillager pillager = pillager(context, FIST_TECHNIQUE, Items.CROSSBOW);
        Cow target = target(context, pillager, 2.0);
        pillager.setTarget(target);
        Set<String> castIds = new LinkedHashSet<>();
        poll(context, pillager, target, OBSERVE_TICKS, () -> {
            String castId = castId(pillager, FIST_TECHNIQUE);
            if (!castId.isEmpty()) {
                castIds.add(castId);
            }
            return target.getHealth() < TARGET_HEALTH;
        }, () -> {
            context.assertTrue(hasCast(pillager, FIST_TECHNIQUE), "外种没有施放袖手拳");
            context.assertTrue(target.getHealth() < TARGET_HEALTH, "袖手拳没有造成真实伤害");
            context.assertTrue(pillager.getMainHandItem().is(Items.CROSSBOW), "袖手拳改变了原版弩");
            LOGGER.info("跨物种袖手拳 caster={} technique={} castIds={} damage={} hand={}",
                    pillager.getStringUUID(), FIST_TECHNIQUE, castIds, TARGET_HEALTH - target.getHealth(),
                    pillager.getMainHandItem());
            cleanup(context, pillager, target);
        });
    }

    @GameTest(maxTicks = TIMEOUT, structure = HumanoidWayfarerTestSupport.STRUCTURE)
    public void evokerUsesEarthLegsAndAppliesLegBenefit(GameTestHelper context) {
        LOGGER.info("GameTest humanoid_cross_species_game_test_evoker_uses_earth_legs_and_applies_leg_benefit");
        floor(context, EVOKER_FLOOR_X, EVOKER_FLOOR_Z);
        Evoker evoker = evoker(context);
        evoker.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(32);
        evoker.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        Cow target = context.spawn(EntityTypes.COW, EVOKER_TARGET_X, ORIGIN, ORIGIN);
        target.setNoAi(true /* aiDisabled */);
        target.setNoGravity(true /* noGravity */);
        target.setSilent(true /* silent */);
        target.setOnGround(true /* onGround */);
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(TARGET_HEALTH);
        target.setHealth((float) TARGET_HEALTH);
        evoker.setTarget(target);
        boolean[] observedBenefit = {false};
        Set<String> castIds = new LinkedHashSet<>();
        poll(context, evoker, target, OBSERVE_TICKS, () -> {
            String castId = castId(evoker, EARTH_TECHNIQUE);
            if (!castId.isEmpty()) {
                castIds.add(castId);
            }
            observedBenefit[0] |= hasActiveBenefit(evoker, EARTH_TECHNIQUE);
            return observedBenefit[0];
        },
                () -> {
                    context.assertTrue(hasCast(evoker, EARTH_TECHNIQUE), "外种没有施放五脏厚土");
                    context.assertTrue(observedBenefit[0],
                            "厚土施放后没有留下腿力状态");
                    LOGGER.info(
                            "跨物种唤魔者厚土腿力 caster={} technique={} castIds={} "
                                    + "benefit={} targetHealth={}",
                            evoker.getStringUUID(), EARTH_TECHNIQUE, castIds, observedBenefit[0], target.getHealth());
                    cleanup(context, evoker, target);
                });
    }

    private static Pillager pillager(GameTestHelper context, String technique, net.minecraft.world.item.Item item) {
        Pillager mob = HumanoidWayfarerTestSupport.pillager(context, HumanoidTier.TRUE_QI);
        HumanoidWayfarerTestSupport.clearKnownTechniques(mob);
        if (technique.equals(DOOR_VOLUME)) {
            TechniqueFabric.service().learn(mob.getUUID(), DOOR_TECHNIQUE);
        } else {
            TechniqueFabric.service().learn(mob.getUUID(), technique);
        }
        HumanoidTechniqueHost.prepare(mob);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(item));
        if (technique.equals(DOOR_VOLUME)) {
            int inserted = MobInventories.inventory(mob).insert(new ItemStack(Items.IRON_AXE));
            context.assertTrue(inserted == 1, "掠夺者背囊没有放入铁斧：" + inserted);
        }
        mob.setNoAi(false /* aiDisabled */);
        mob.setNoGravity(false /* noGravity */);
        return mob;
    }

    private static boolean hasActiveBenefit(LivingEntity entity, String technique) {
        return TechniqueStatusBenefits.tissues().stream()
                .filter(registered -> registered.technique().getId().equals(technique))
                .anyMatch(registered -> entity.hasEffect(registered.effect()));
    }

    private static void floor(GameTestHelper context, int width, int length) {
        for (int x = 1; x <= width; x++) {
            for (int z = 1; z <= length; z++) {
                context.setBlock(new BlockPos(x, ORIGIN - 1, z), Blocks.STONE);
            }
        }
    }

    private static Cow target(GameTestHelper context, Pillager mob, double distance) {
        Cow target = context.spawn(EntityTypes.COW, ORIGIN, ORIGIN, ORIGIN);
        target.setNoAi(true /* aiDisabled */);
        target.setNoGravity(true /* noGravity */);
        target.setSilent(true /* silent */);
        target.setOnGround(true /* onGround */);
        target.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(TARGET_HEALTH);
        target.setHealth((float) TARGET_HEALTH);
        target.setPos(mob.position().add(0, 0, distance));
        return target;
    }

    private static Evoker evoker(GameTestHelper context) {
        Evoker mob = HumanoidEvokerGameTest.born(context, "cross-species-earth-legs",
                HumanoidTier.WANDERING_QI, EARTH, new BlockPos(ORIGIN, ORIGIN, ORIGIN), body -> body);
        HumanoidTechniqueHost.prepare(mob);
        HumanoidWayfarerTestSupport.clearKnownTechniques(mob);
        TechniqueFabric.service().learn(mob.getUUID(), EARTH_TECHNIQUE);
        HumanoidTechniqueHost.prepare(mob);
        return mob;
    }

    private static void placeDoor(GameTestHelper context) {
        BlockPos lower = door(context);
        BlockState state = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        context.setBlock(lower, state);
        context.setBlock(lower.above(), state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static BlockPos door(GameTestHelper context) {
        return context.absolutePos(new BlockPos(ORIGIN, ORIGIN, DOOR_Z));
    }

    private static boolean hasCast(LivingEntity entity, String technique) {
        TechniqueProgress progress = progress(entity);
        if (progress.hasActiveCast()
                && progress.getActiveCast().getRequest().getTechnique().getId().equals(technique)) {
            return true;
        }
        return progress.hasLastCast() && progress.getLastCast().getTechnique().getId().equals(technique);
    }

    private static String castId(LivingEntity entity, String technique) {
        TechniqueProgress progress = progress(entity);
        if (progress.hasActiveCast()
                && progress.getActiveCast().getRequest().getTechnique().getId().equals(technique)) {
            return progress.getActiveCast().getRequest().getCastId();
        }
        if (progress.hasLastCast() && progress.getLastCast().getTechnique().getId().equals(technique)) {
            return progress.getLastCast().getCastId();
        }
        return "";
    }

    private static TechniqueProgress progress(LivingEntity entity) {
        var snapshot = ((HumanoidEntityAccess) entity).meridian$humanoidBody().snapshot();
        return TechniqueState.read(BodyProtobuf.fromMessage(snapshot));
    }

    private static boolean hasPackItem(Pillager mob, net.minecraft.world.item.Item item) {
        return MobInventories.inventory(mob).snapshot().stream().anyMatch(stack -> stack.is(item));
    }

    private static void poll(GameTestHelper context, LivingEntity actor, LivingEntity target, int remaining,
                             java.util.function.BooleanSupplier ready, Runnable finish) {
        if (ready.getAsBoolean()) {
            finish.run();
            return;
        }
        if (remaining == 0) {
            context.fail("跨物种施放超时 actor=" + actor.getType() + " targetHealth=" + target.getHealth()
                    + " actorHealth=" + actor.getHealth());
            return;
        }
        context.runAfterDelay(1, () -> poll(context, actor, target, remaining - 1, ready, finish));
    }

    private static void cleanup(GameTestHelper context, Entity... entities) {
        for (Entity entity : entities) {
            entity.discard();
        }
        context.succeed();
    }

    private record VillagerTarget(net.minecraft.world.entity.npc.villager.Villager entity) {
        private static VillagerTarget create(GameTestHelper context, Witch witch, double distance) {
            var villager = context.spawn(EntityTypes.VILLAGER, ORIGIN, ORIGIN, ORIGIN);
            villager.setNoAi(true /* aiDisabled */);
            villager.setNoGravity(true /* noGravity */);
            villager.setPos(witch.position().add(0, 0, distance));
            villager.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)
                    .setBaseValue(TARGET_HEALTH);
            villager.setHealth((float) TARGET_HEALTH);
            return new VillagerTarget(villager);
        }
    }
}
