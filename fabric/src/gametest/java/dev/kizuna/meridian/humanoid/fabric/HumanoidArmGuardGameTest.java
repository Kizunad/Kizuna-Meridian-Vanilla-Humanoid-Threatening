package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.Decision;
import dev.kizuna.meridian.humanoid.server.HumanoidBodyGenerator;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.server.TechniqueService.CastObserved;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import static dev.kizuna.meridian.humanoid.fabric.HumanoidGuardStaminaGameTest.available;
import static dev.kizuna.meridian.humanoid.fabric.HumanoidGuardStaminaGameTest.restoreTo;
import static dev.kizuna.meridian.humanoid.fabric.HumanoidGuardStaminaGameTest.spend;

/** 每个主体都由真实 serverAiStep 驱动。固定世界种子与 UUID 混合后的随机流。 */
public final class HumanoidArmGuardGameTest {
    private static final String ARENA = "meridian_humanoid_test:retreat";
    private static final BlockPos ORIGIN = new BlockPos(6, 1, 6);
    private static final int TIMEOUT = 100;
    private static final int MATRIX_TICKS = 24;
    private static final int MORTAL_DELAY = 8;
    private static final int WARMUP_TICKS = 8;
    private static final long MILLIS_PER_TICK = 50;
    private static final int QUIET_TICKS = 20;
    private static final int RECOVERY_TICKS = 6;
    private static final int REST_TICKS = 20;
    private static final double ENTER = 3.5;
    private static final double EXIT = 4;
    private static final double OUTSIDE = 4.25;
    private static final double ARCHER_DISTANCE = 24;
    private static final double LOW_BALANCE = 0.05;
    // 四组首抽依次约为 0.099、0.281、0.304、0.731；第二抽均低于 0.1。
    private static final long LOW_ROLL = 4112;
    private static final long MIDDLE_ROLL = 6151;
    private static final long MISS_ROLL = 3;
    private static final float REVERSE = 180;
    private static final double HALF_ANGLE = 60;
    private static final double SIDE_ANGLE = 61;

    @GameTest(structure = ARENA, maxTicks = TIMEOUT)
    public void villagerRaisesAtMeleeBoundaryAndLowersBeyondFour(GameTestHelper context) {
        var scene = new Scene(context, EntityTypes.VILLAGER, HumanoidTier.MORTAL, LOW_ROLL);
        scene.melee(OUTSIDE);
        scene.after(1, () -> {
            scene.counts(0, 0);
            scene.position(ENTER);
            scene.whenRaised(MORTAL_DELAY, () -> {
                scene.position(EXIT);
                scene.after(1, () -> {
                    scene.holding();
                    scene.position(OUTSIDE);
                    scene.after(1, () -> {
                        scene.lowered();
                        scene.after(RECOVERY_TICKS + 1, () -> scene.checkRest());
                    });
                });
            });
        });
    }

    @GameTest(structure = ARENA, maxTicks = TIMEOUT)
    public void pillagerHoldsOnlyWhileArcherAims(GameTestHelper context) {
        var scene = Scene.mortalTagged(context, EntityTypes.PILLAGER, LOW_ROLL);
        assertEmptyBirthVolumes(context, scene);
        scene.archer();
        scene.whenRaised(MORTAL_DELAY, () -> {
            scene.source.stopUsingItem();
            scene.after(1, () -> {
                scene.lowered();
                context.succeed();
            });
        });
    }

    @GameTest(structure = ARENA, maxTicks = TIMEOUT)
    public void vindicatorHoldsBelowSelectionThresholdUntilQuiet(GameTestHelper context) {
        var scene = Scene.mortalTagged(context, EntityTypes.VINDICATOR, LOW_ROLL);
        assertEmptyBirthVolumes(context, scene);
        scene.melee(ENTER);
        scene.whenRaised(MORTAL_DELAY, () -> {
            scene.drain();
            scene.after(1, () -> {
                scene.holding();
                restoreTo(scene.body, 0);
                scene.source.discard();
                scene.after(QUIET_TICKS - 1, () -> {
                    scene.holding();
                    scene.after(1, () -> {
                        scene.lowered();
                        context.succeed();
                    });
                });
            });
        });
    }

    @GameTest(structure = ARENA, maxTicks = TIMEOUT)
    public void illusionerGetsOneRollPerThreat(GameTestHelper context) {
        var scene = new Scene(context, EntityTypes.ILLUSIONER, HumanoidTier.MORTAL, MIDDLE_ROLL);
        var successes = new AtomicInteger();
        Consumer<CastObserved> listener = event -> {
            if (event.entityUuid().equals(scene.mob.getUUID()) && event.outcome().getPhase() == CastPhase.WARMUP
                    && HumanoidTechniqueHost.castingGuard(scene.mob)) {
                successes.incrementAndGet();
            }
        };
        var service = TechniqueFabric.service();
        service.addCastListener(listener);
        scene.melee(ENTER);
        scene.after(MATRIX_TICKS, () -> {
            service.removeCastListener(listener);
            int actual = successes.get();
            context.assertTrue(actual == 0, "抽空后仍成功起守 " + actual + " 次");
            scene.counts(1, 0);
            scene.position(OUTSIDE);
            scene.after(1, () -> {
                scene.position(ENTER);
                scene.after(MORTAL_DELAY + WARMUP_TICKS + 1, () -> {
                    scene.counts(2, 1);
                    scene.holding();
                    context.succeed();
                });
            });
        });
    }

    @GameTest(structure = ARENA, maxTicks = TIMEOUT)
    public void evokerRechecksFatigueAtDeadline(GameTestHelper context) {
        var scene = Scene.mortalTagged(context, EntityTypes.EVOKER, LOW_ROLL);
        assertEmptyBirthVolumes(context, scene);
        scene.melee(ENTER);
        scene.after(1, () -> {
            scene.counts(1, 0);
            scene.drain();
            scene.after(MORTAL_DELAY, () -> {
                scene.counts(1, 0);
                restoreTo(scene.body, 0);
                scene.after(MATRIX_TICKS, () -> {
                    scene.counts(1, 0);
                    context.succeed();
                });
            });
        });
    }

    private static void assertEmptyBirthVolumes(GameTestHelper context, Scene scene) {
        var grant = ((HumanoidEntityAccess) scene.mob).meridian$birthVolumes();
        context.assertTrue(HumanoidTier.of(scene.body.snapshot()) == HumanoidTier.MORTAL,
                "凡人档标签没有指定出生档");
        context.assertTrue(grant != null && grant.volumes().isEmpty(), "凡人档生人抽到了出生外卷");
    }

    @GameTest(structure = ARENA, maxTicks = TIMEOUT)
    public void traderSkipsLowBalanceThenRaisesOnNewThreat(GameTestHelper context) {
        var scene = new Scene(context, EntityTypes.WANDERING_TRADER, HumanoidTier.MORTAL, LOW_ROLL);
        scene.drain();
        scene.melee(ENTER);
        scene.after(MATRIX_TICKS, () -> {
            scene.counts(0, 0);
            var decisions = HumanoidBrain.decisions(scene.mob.getUUID());
            context.assertTrue(!decisions.isEmpty(), "低体力时普通决策停摆");
            restoreTo(scene.body, 0);
            scene.after(MORTAL_DELAY, () -> {
                scene.counts(0, 0);
                scene.position(OUTSIDE);
                scene.after(1, () -> {
                    scene.position(ENTER);
                    scene.whenRaised(MORTAL_DELAY, context::succeed);
                });
            });
        });
    }

    @GameTest(structure = ARENA, maxTicks = TIMEOUT)
    public void witchIgnoresRearAndDoesNotRerollFailure(GameTestHelper context) {
        var scene = Scene.mortalTagged(context, EntityTypes.WITCH, MISS_ROLL);
        var grant = ((HumanoidEntityAccess) scene.mob).meridian$birthVolumes();
        context.assertTrue(HumanoidTier.of(scene.body.snapshot()) == HumanoidTier.MORTAL,
                "凡人档标签没有指定出生档");
        context.assertTrue(grant != null && grant.volumes().isEmpty(), "凡人档女巫抽到了出生外卷");
        scene.melee(-ENTER);
        scene.after(1, () -> {
            scene.counts(0, 0);
            scene.position(ENTER);
            scene.after(MATRIX_TICKS, () -> {
                scene.counts(1, 0);
                context.succeed();
            });
        });
    }

    @GameTest(structure = ARENA, maxTicks = TIMEOUT)
    public void brokenGuardChecksNewThreatOnlyOnce(GameTestHelper context) {
        var scene = new Scene(context, EntityTypes.VINDICATOR, HumanoidTier.MORTAL, LOW_ROLL);
        scene.melee(ENTER);
        scene.whenRaised(MORTAL_DELAY, () -> {
            spend(scene.body, available(scene.body));
            scene.lowered();
            scene.position(OUTSIDE);
            // 破防禁守 35 tick，恢复期与大脑再抬间隔结束时仍未到禁守截止。
            scene.after(RECOVERY_TICKS + REST_TICKS + 2, () -> {
                restoreTo(scene.body, 0);
                int before = scene.guard().checks();
                scene.position(ENTER);
                scene.after(MATRIX_TICKS, () -> {
                    context.assertTrue(scene.guard().checks() == before + 1, "禁守中逐 tick 重排");
                    scene.counts(1, 1);
                    context.succeed();
                });
            });
        });
    }

    @GameTest(structure = ARENA, maxTicks = TIMEOUT)
    public void vanillaShieldKeepsBodyRevision(GameTestHelper context) {
        var scene = new Scene(context, EntityTypes.VILLAGER, HumanoidTier.MORTAL, LOW_ROLL);
        scene.mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        scene.melee(ENTER);
        scene.after(MATRIX_TICKS, () -> {
            scene.counts(0, 0);
            scene.mob.stopUsingItem();
            var snapshot = HumanoidTechniqueHost.prepare(scene.mob).body().currentBody();
            var turn = new HumanoidActions.Turn(scene.mob, new HumanoidMind(), snapshot, null, Optional.empty());
            HumanoidActions.execute(turn, Decision.of(Decision.Action.DODGE, "盾"));
            context.assertTrue(scene.mob.getUseItem().is(Items.SHIELD), "原版闪避分支没有举盾");
            long revision = scene.body.currentBody().revision();
            context.assertTrue(revision == snapshot.revision(), "举盾写入通用格挡费用");
            context.succeed();
        });
    }

    @GameTest(structure = ARENA, maxTicks = TIMEOUT)
    public void approachNeedsHorizontalFrontAndVisibleAim(GameTestHelper context) {
        var scene = new Scene(context, EntityTypes.VILLAGER, HumanoidTier.MORTAL, LOW_ROLL);
        var rules = HumanoidBrain.config().threat().guard();
        scene.position(ENTER);
        scene.source.setTarget(scene.mob);
        context.assertTrue(HumanoidGuardThreat.find(scene.mob, null, rules) == null, "只盯目标就起守");
        scene.melee(ENTER);
        scene.mob.setYRot((float) HALF_ANGLE);
        context.assertTrue(HumanoidGuardThreat.find(scene.mob, null, rules) != null, "正面边界被排除");
        scene.mob.setYRot((float) SIDE_ANGLE);
        context.assertTrue(HumanoidGuardThreat.find(scene.mob, null, rules) == null, "侧方进入守势");
        scene.position(0);
        context.assertTrue(HumanoidGuardThreat.find(scene.mob, null, rules) == null, "无水平来向仍可起守");
        scene.archer();
        scene.position(ARCHER_DISTANCE + 1);
        context.assertTrue(HumanoidGuardThreat.find(scene.mob, null, rules) == null, "超过远程距离仍可起守");
        scene.position(ARCHER_DISTANCE);
        scene.source.setYRot(0);
        scene.source.setYHeadRot(0);
        context.assertTrue(HumanoidGuardThreat.find(scene.mob, null, rules) == null, "射手背对仍可起守");
        scene.position(ARCHER_DISTANCE);
        var wall = BlockPos.containing(scene.origin.add(0, 1, EXIT));
        context.getLevel().setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
        scene.mob.getSensing().tick();
        context.assertTrue(HumanoidGuardThreat.find(scene.mob, null, rules) == null, "隔墙蓄射仍可起守");
        context.succeed();
    }

    private static final class Scene {
        private enum BirthMode { SPECIFIED_TIER, MORTAL_TAG }

        private final GameTestHelper context;
        private final Mob mob;
        private final Mob source;
        private final HumanoidBodyState body;
        private final Vec3 origin;

        Scene(GameTestHelper context, EntityType<? extends Mob> species, HumanoidTier tier, long roll) {
            this(context, species, tier, roll, BirthMode.SPECIFIED_TIER);
        }

        static Scene mortalTagged(GameTestHelper context, EntityType<? extends Mob> species, long roll) {
            return new Scene(context, species, HumanoidTier.MORTAL, roll, BirthMode.MORTAL_TAG);
        }

        private Scene(GameTestHelper context, EntityType<? extends Mob> species, HumanoidTier tier, long roll,
                      BirthMode birthMode) {
            this.context = context;
            var level = context.getLevel();
            origin = Vec3.atBottomCenterOf(context.absolutePos(ORIGIN));
            mob = species.create(level, EntitySpawnReason.COMMAND);
            long key = origin.hashCode();
            long mix = HumanoidBodyGenerator.seedFor(level.getSeed(), new UUID(0, key));
            mob.setUUID(new UUID(mix ^ roll, key));
            mob.snapTo(origin.x, origin.y, origin.z, 0, 0);
            mob.setNoGravity(true /* noGravity */);
            mob.setPersistenceRequired();
            mob.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
            if (birthMode == BirthMode.MORTAL_TAG) {
                mob.addTag(HumanoidTier.TAG_PREFIX + HumanoidTier.MORTAL.id());
            } else {
                HumanoidSpeciesBirths.birth(mob, tier);
            }
            mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            context.assertTrue(level.addFreshEntity(mob), "生人未入场");
            body = HumanoidTechniqueHost.prepare(mob).body();
            source = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
            source.setNoAi(true /* aiDisabled */);
            source.setNoGravity(true /* noGravity */);
            position(OUTSIDE);
            context.assertTrue(level.addFreshEntity(source), "威胁未入场");
        }

        void position(double distance) {
            mob.setYRot(0);
            mob.setYHeadRot(0);
            source.snapTo(origin.x, origin.y, origin.z + distance, REVERSE, 0);
        }

        void melee(double distance) {
            position(distance);
            source.setTarget(mob);
            swing();
        }

        void swing() {
            if (source.isAlive() && !source.isRemoved() && source.getMainHandItem().isEmpty()) {
                source.swing(InteractionHand.MAIN_HAND);
                after(1, this::swing);
            }
        }

        void archer() {
            position(ARCHER_DISTANCE);
            source.setTarget(mob);
            source.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            source.startUsingItem(InteractionHand.MAIN_HAND);
        }

        void after(int ticks, Runnable step) {
            context.runAfterDelay(ticks, step);
        }

        void whenRaised(int delay, Runnable next) {
            after(delay + 1, () -> {
                counts(1, 1);
                var active = body.techniqueProgress().getActiveCast();
                context.assertTrue(active.getRequest().getTechnique().equals(HumanoidArmGuard.GUARD),
                        "反应到期未请求正式格挡");
                long started = active.getStartedMillis();
                context.assertTrue(started - guard().selectedMillis() == delay * MILLIS_PER_TICK, "反应延迟不符");
                context.assertTrue(active.getWarmupEndMillis() - started == WARMUP_TICKS * MILLIS_PER_TICK,
                        "正式起手时长不符");
                lowered();
                after(WARMUP_TICKS, () -> {
                    holding();
                    next.run();
                });
            });
        }

        void checkRest() {
            position(ENTER);
            after(REST_TICKS - 1, () -> {
                counts(1, 1);
                position(OUTSIDE);
                after(1, () -> {
                    position(ENTER);
                    after(MORTAL_DELAY + WARMUP_TICKS + 1, () -> {
                        counts(2, 2);
                        holding();
                        context.succeed();
                    });
                });
            });
        }

        HumanoidArmGuard guard() {
            return HumanoidBrain.guard(mob);
        }

        void counts(int candidates, int requests) {
            var guard = guard();
            int rolls = guard.candidates();
            int casts = guard.requests();
            context.assertTrue(rolls == candidates, "候选抽签应为 " + candidates + " 实为 " + rolls);
            context.assertTrue(casts == requests, "正式请求应为 " + requests + " 实为 " + casts);
        }

        void drain() {
            spend(body, available(body) * (1 - LOW_BALANCE));
        }

        void holding() {
            context.assertTrue(TechniqueFabric.service().activeGuard(mob.getUUID()).isPresent(), "应已成架");
        }

        void lowered() {
            context.assertTrue(TechniqueFabric.service().activeGuard(mob.getUUID()).isEmpty(), "当前不应有守窗");
        }
    }
}
