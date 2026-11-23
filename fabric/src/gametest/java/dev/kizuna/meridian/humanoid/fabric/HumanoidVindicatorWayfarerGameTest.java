package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.human.fabric.HumanTechniqueArrow;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.inventory.MobInventory;
import dev.kizuna.meridian.inventory.MobInventories;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.server.TechniqueState;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 卫道士用真实大脑从背囊换弓、退到射程并发出带功法标记的箭。 */
public final class HumanoidVindicatorWayfarerGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_humanoid_test");
    private static final String STRUCTURE = "meridian_humanoid_test:archery";
    private static final String ENVIRONMENT = "meridian_humanoid_test:vindicator_wayfarer";
    private static final String WAYFARER_PREFIX = "meridian_wayfarer:";
    private static final Set<String> REQUIRED_TECHNIQUES = Set.of(
            WAYFARER_PREFIX + "full_draw",
            WAYFARER_PREFIX + "pearls",
            WAYFARER_PREFIX + "bow_strike",
            WAYFARER_PREFIX + "dry_draw",
            WAYFARER_PREFIX + "repeater");
    private static final int ORIGIN = 2;
    private static final int FLOOR_WIDTH = 6;
    private static final int FLOOR_MIN_Z = -20;
    private static final int FLOOR_MAX_Z = 36;
    private static final int OBSERVATION_TICKS = 800;
    private static final int TIMEOUT = OBSERVATION_TICKS + 20;
    private static final double TARGET_DISTANCE = 12;
    private static final double TARGET_APPROACH_SPEED = -0.2;
    private static final double NEAR_DISTANCE = 8;
    private static final double RETREAT_TRIGGER_DISTANCE = 4;
    private static final double MIN_RETREAT_DISTANCE = 0.5;
    private static final double MIN_RETREATED_DISTANCE = RETREAT_TRIGGER_DISTANCE + MIN_RETREAT_DISTANCE;
    private static final double MELEE_APPROACH_DISTANCE = 2.5;
    private static final double TARGET_HEALTH = 1000;
    private static final double TARGET_KNOCKBACK_RESISTANCE = 1;
    private static final double SEARCH_RADIUS = 40;

    @GameTest(maxTicks = TIMEOUT, structure = STRUCTURE, environment = ENVIRONMENT)
    public void vindicatorSwapsBowRetreatsAndShootsTechniqueArrow(GameTestHelper context) {
        floor(context);
        Vindicator mob = vindicator(context);
        Cow target = target(context, mob);
        mob.setNoGravity(false /* noGravity */);
        mob.setNoAi(false /* aiDisabled */);
        mob.setTarget(target);
        new Watch(context, mob, target).tick(OBSERVATION_TICKS);
    }

    private static Vindicator vindicator(GameTestHelper context) {
        Vindicator mob = EntityTypes.VINDICATOR.create(context.getLevel(), EntitySpawnReason.COMMAND);
        ((HumanoidEntityAccess) mob).meridian$skipVolumeBirth();
        var position = context.absolutePos(new BlockPos(ORIGIN, ORIGIN, ORIGIN));
        mob.snapTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5, 0, 0);
        mob.setNoAi(true /* aiDisabled */);
        mob.setNoGravity(true /* noGravity */);
        mob.setOnGround(true /* onGround */);
        mob.setPersistenceRequired();
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
        HumanoidSpeciesBirths.birth(mob, HumanoidTier.TRUE_QI);
        context.assertTrue(context.getLevel().addFreshEntity(mob), "卫道士没有加入世界");
        HumanoidTechniqueHost.prepare(mob);
        TechniqueFabric.service().learn(mob.getUUID(), WAYFARER_PREFIX + "bow_upper");
        TechniqueFabric.service().learn(mob.getUUID(), WAYFARER_PREFIX + "repeater");
        HumanoidWayfarerTestSupport.prepareQi(context, mob);
        HumanoidWayfarerTestSupport.clearKnownTechniques(mob);
        TechniqueFabric.service().learn(mob.getUUID(), WAYFARER_PREFIX + "bow_upper");
        TechniqueFabric.service().learn(mob.getUUID(), WAYFARER_PREFIX + "repeater");
        var known = TechniqueState.read(BodyProtobuf.fromMessage(
                HumanoidTechniqueHost.prepare(mob).body().snapshot())).getKnownList();
        var knownIds = known.stream()
                .map(entry -> entry.getTechnique().getId())
                .collect(Collectors.toSet());
        context.assertTrue(knownIds.equals(REQUIRED_TECHNIQUES),
                "卫道士测试只应保留指定的两卷展开式：" + knownIds);
        var bow = new ItemStack(Items.BOW);
        int inserted = MobInventories.inventory(mob).insert(bow);
        context.assertTrue(inserted == 1, "卫道士背囊没有放入弓：" + inserted);
        return mob;
    }

    private static Cow target(GameTestHelper context, Vindicator mob) {
        Cow target = context.spawn(EntityTypes.COW, ORIGIN, ORIGIN, ORIGIN);
        target.setNoAi(true /* aiDisabled */);
        target.setNoGravity(true /* noGravity */);
        target.setOnGround(true /* onGround */);
        target.setSilent(true /* silent */);
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(TARGET_HEALTH);
        target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(TARGET_KNOCKBACK_RESISTANCE);
        target.setHealth((float) TARGET_HEALTH);
        target.setPos(mob.position().add(0, 0, TARGET_DISTANCE));
        target.setDeltaMovement(Vec3.ZERO);
        mob.setTarget(target);
        return target;
    }

    private static void floor(GameTestHelper context) {
        for (int x = 1; x <= FLOOR_WIDTH; x++) {
            for (int z = FLOOR_MIN_Z; z <= FLOOR_MAX_Z; z++) {
                context.setBlock(new BlockPos(x, ORIGIN - 1, z), Blocks.STONE);
            }
        }
    }

    private static final class Watch {
        private final GameTestHelper context;
        private final Vindicator mob;
        private final Cow target;
        private final ItemStack originalAxe;
        private final Set<UUID> arrows = new LinkedHashSet<>();
        private final Set<String> casts = new LinkedHashSet<>();
        private boolean heldBow;
        private boolean storedAxe;
        private boolean arrowObserved;
        private boolean targetApproaching;
        private boolean enteredNearRange;
        private boolean enteredRetreatRange;
        private boolean restoredAxeObserved;
        private boolean bowLost;
        private boolean meleeAttackObserved;
        private boolean nearDiagnosticLogged;
        private boolean retreatDiagnosticLogged;
        private double maximumDistance;
        private double maximumAfterRetreat;
        private float targetHealthAtBowLoss;

        private Watch(GameTestHelper context, Vindicator mob, Cow target) {
            this.context = context;
            this.mob = mob;
            this.target = target;
            this.originalAxe = mob.getMainHandItem().copy();
        }

        private void tick(int remaining) {
            moveApproachingTarget();
            TechniqueProgress progress = observeProgress();
            observeEquipment();
            double distance = observeDistance();
            logDiagnostics(distance);
            observeArrows();
            updateRecovery(distance, progress);
            observeMeleeAttack();
            if (shouldFinish(progress)) {
                finish();
                return;
            }
            if (remaining == 0) {
                fail();
                return;
            }
            context.runAfterDelay(1, () -> tick(remaining - 1));
        }

        private void moveApproachingTarget() {
            target.setDeltaMovement(Vec3.ZERO);
            if (!targetApproaching || bowLost) {
                return;
            }
            target.setPos(target.position().add(0, 0, TARGET_APPROACH_SPEED));
            target.setDeltaMovement(new Vec3(0, 0, TARGET_APPROACH_SPEED));
        }

        private TechniqueProgress observeProgress() {
            var snapshot = ((HumanoidEntityAccess) mob).meridian$humanoidBody().snapshot();
            TechniqueProgress progress = TechniqueState.read(BodyProtobuf.fromMessage(snapshot));
            if (progress.hasActiveCast()) {
                casts.add(progress.getActiveCast().getRequest().getTechnique().getId());
            }
            return progress;
        }

        private void observeEquipment() {
            heldBow |= mob.getMainHandItem().is(Items.BOW);
            storedAxe |= MobInventories.inventory(mob).snapshot().stream()
                    .anyMatch(stack -> ItemStack.isSameItemSameComponents(stack, originalAxe));
        }

        private double observeDistance() {
            double distance = mob.distanceTo(target);
            maximumDistance = Math.max(maximumDistance, distance);
            if (distance < RETREAT_TRIGGER_DISTANCE) {
                enteredRetreatRange = true;
            }
            if (enteredRetreatRange) {
                maximumAfterRetreat = Math.max(maximumAfterRetreat, distance);
            }
            return distance;
        }

        private void logDiagnostics(double distance) {
            if (!nearDiagnosticLogged && distance < NEAR_DISTANCE) {
                nearDiagnosticLogged = true;
                LOGGER.info("卫道士行矢近距诊断 tick={} mob={} target={} distance={} "
                                + "directive={} navigationDone={} navigationTarget={} "
                                + "pathTarget={} targetVelocity={}",
                        mob.tickCount, mob.position(), target.position(), distance,
                        HumanoidBrain.directive(mob.getUUID()), mob.getNavigation().isDone(),
                        mob.getNavigation().getTargetPos(), pathTarget(), target.getDeltaMovement());
            }
            if (!retreatDiagnosticLogged && distance < RETREAT_TRIGGER_DISTANCE) {
                retreatDiagnosticLogged = true;
                LOGGER.info("卫道士行矢退距诊断 tick={} mob={} target={} distance={} "
                                + "directive={} navigationDone={} navigationTarget={} "
                                + "pathTarget={} targetVelocity={}",
                        mob.tickCount, mob.position(), target.position(), distance,
                        HumanoidBrain.directive(mob.getUUID()), mob.getNavigation().isDone(),
                        mob.getNavigation().getTargetPos(), pathTarget(), target.getDeltaMovement());
            }
        }

        private String pathTarget() {
            var path = mob.getNavigation().getPath();
            if (path == null) {
                return "none";
            }
            return path.getTarget().toString();
        }

        private void observeArrows() {
            var freshArrows = mob.level().getEntitiesOfClass(AbstractArrow.class,
                            mob.getBoundingBox().inflate(SEARCH_RADIUS), arrow -> arrow.getOwner() == mob).stream()
                    .filter(arrow -> arrows.add(arrow.getUUID()))
                    .toList();
            arrowObserved |= freshArrows.stream().anyMatch(HumanTechniqueArrow.class::isInstance);
            if (arrowObserved && !targetApproaching) {
                targetApproaching = true;
            }
        }

        private void updateRecovery(double distance, TechniqueProgress progress) {
            restoredAxeObserved |= restored();
            enteredNearRange |= distance < NEAR_DISTANCE;
            if (!bowLost && arrowObserved && enteredNearRange && !progress.hasActiveCast()
                    && restoredAxeObserved && maximumAfterRetreat >= MIN_RETREATED_DISTANCE) {
                loseStoredBow();
            }
        }

        private void observeMeleeAttack() {
            var damage = target.getLastDamageSource();
            meleeAttackObserved |= bowLost
                    && mob.getMainHandItem().is(Items.IRON_AXE)
                    && mob.swinging
                    && damage != null
                    && damage.getDirectEntity() == mob
                    && target.getHealth() < targetHealthAtBowLoss;
        }

        private boolean shouldFinish(TechniqueProgress progress) {
            return arrowObserved && enteredNearRange && !progress.hasActiveCast() && restoredAxeObserved
                    && bowLost && meleeAttackObserved && maximumAfterRetreat >= MIN_RETREATED_DISTANCE;
        }

        private boolean restored() {
            ItemStack hand = mob.getMainHandItem();
            long bows = MobInventories.inventory(mob).snapshot().stream()
                    .filter(stack -> stack.is(Items.BOW))
                    .count();
            return ItemStack.isSameItemSameComponents(hand, originalAxe) && bows == 1;
        }

        private void loseStoredBow() {
            var inventory = MobInventories.inventory(mob);
            for (int slot = 0; slot < MobInventory.SIZE; slot++) {
                if (inventory.peek(slot).is(Items.BOW)) {
                    ItemStack removed = inventory.extract(slot, 1);
                    context.assertTrue(removed.is(Items.BOW), "还斧后没有从背囊移除备用弓");
                    targetHealthAtBowLoss = target.getHealth();
                    mob.swinging = false;
                    bowLost = true;
                    target.setPos(mob.position().add(0, 0, MELEE_APPROACH_DISTANCE - MIN_RETREAT_DISTANCE));
                    target.setDeltaMovement(Vec3.ZERO);
                    return;
                }
            }
            context.fail("卫道士还斧后背囊没有可移除的弓");
        }

        private void finish() {
            LOGGER.info("卫道士行矢证据 subject={} casts={} arrows={} maximumDistance={} "
                            + "maximumAfterRetreat={} finalDistance={} stance={} directive={} directing={} "
                            + "closeStrike={} beforeRecovery={} hand={} pack={} decisions={}",
                    mob.getStringUUID(), casts, arrows.size(), maximumDistance, maximumAfterRetreat,
                    mob.distanceTo(target),
                    HumanoidBrain.stance(mob.getUUID()), HumanoidBrain.directive(mob.getUUID()),
                    HumanoidBrain.directing(mob), HumanoidTechniqueHost.castingCloseStrike(mob),
                    HumanoidTechniqueHost.beforeRecovery(mob), mob.getMainHandItem(),
                    MobInventories.inventory(mob).snapshot(), HumanoidBrain.decisions(mob.getUUID()));
            context.assertTrue(heldBow, "卫道士没有从背囊换出弓");
            context.assertTrue(storedAxe, "原斧没有在施放期间进入背囊");
            context.assertTrue(casts.stream().anyMatch(id -> id.startsWith(WAYFARER_PREFIX)),
                    "没有记录行矢施放式");
            context.assertTrue(maximumAfterRetreat >= MIN_RETREATED_DISTANCE,
                    "目标逼近后卫道士没有退回射程：" + maximumAfterRetreat);
            context.assertTrue(restoredAxeObserved, "卫道士施放后没有还回原斧");
            context.assertTrue(bowLost, "卫道士还斧后没有失去备用弓");
            context.assertTrue(meleeAttackObserved, "卫道士还斧后没有恢复原版近战");
            LOGGER.info("卫道士行矢通过 subject={} casts={} arrows={} maximumDistance={} "
                            + "finalDistance={} hand={} pack={}",
                    mob.getStringUUID(), casts, arrows.size(), maximumDistance, mob.distanceTo(target),
                    mob.getMainHandItem(), MobInventories.inventory(mob).snapshot());
            mob.discard();
            target.discard();
            context.succeed();
        }

        private void fail() {
            var body = BodyProtobuf.fromMessage(((HumanoidEntityAccess) mob).meridian$humanoidBody().snapshot());
            var opponent = new HumanoidOpponent(target, java.util.Optional.empty());
            var readout = HumanoidRepertoires.read(mob, body, java.util.Optional.of(opponent), ignored -> false);
            var facts = HumanoidWayfarerFacts.read(mob, target);
            String diagnosis = "卫道士行矢超时 subject=" + mob.getStringUUID()
                    + " casts=" + casts + " arrows=" + arrows.size() + " techniqueArrow=" + arrowObserved
                    + " heldBow=" + heldBow + " storedAxe=" + storedAxe
                    + " maximumDistance=" + maximumDistance + " enteredRetreatRange=" + enteredRetreatRange
                    + " maximumAfterRetreat=" + maximumAfterRetreat + " restoredAxe=" + restoredAxeObserved
                    + " meleeAttack=" + meleeAttackObserved
                    + " decisions=" + HumanoidBrain.decisions(mob.getUUID())
                    + " stance=" + HumanoidBrain.stance(mob.getUUID()) + " facts=" + facts
                    + " volleyReach=" + readout.volleyReach() + " target=" + readout.context().combat().target()
                    + " means=" + readout.means() + " directive=" + HumanoidBrain.directive(mob.getUUID())
                    + " hand=" + mob.getMainHandItem() + " pack=" + MobInventories.inventory(mob).snapshot();
            mob.discard();
            target.discard();
            context.fail(diagnosis);
        }
    }
}
