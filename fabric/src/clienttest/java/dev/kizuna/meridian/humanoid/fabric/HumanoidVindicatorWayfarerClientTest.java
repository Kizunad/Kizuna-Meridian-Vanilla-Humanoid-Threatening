package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.human.fabric.HumanTechniqueArrow;
import dev.kizuna.meridian.human.server.HumanOpening;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.inventory.MobInventories;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpening;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpeningStage;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.SetAddonProgress;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueAvailability;
import dev.kizuna.meridian.technique.server.TechniqueBinder;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import net.minecraft.network.chat.Component;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.CameraType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 卫道士背囊换弓、退射和收势还斧的第三人称客户端录像。 */
public final class HumanoidVindicatorWayfarerClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_humanoid_wayfarer_film");
    private static final String WAYFARER_PREFIX = "meridian_wayfarer:";
    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 720;
    private static final int FRAME_COUNT = 480;
    private static final int TICKS_PER_FRAME = 1;
    private static final int CAMERA_SETUP_TICKS = 10;
    private static final int LEAD_IN_FRAME_COUNT = 40;
    private static final int CLEAR_RADIUS = 20;
    private static final int CLEAR_HEIGHT = 8;
    private static final int SCENE_FLOOR_Y = 64;
    private static final double MOB_X = 0.5;
    private static final double MOB_Z = 0.5;
    private static final double TARGET_X = 0.5;
    private static final double TARGET_Z = 12.5;
    private static final double CAMERA_X = -12.0;
    private static final double CAMERA_Z = 6.5;
    private static final double CAMERA_EYE_HEIGHT = 2.8;
    private static final float CAMERA_YAW = -90.0f;
    private static final float CAMERA_PITCH = 8.0f;
    private static final double TARGET_APPROACH_SPEED = 0.03;
    private static final double TARGET_PASS_DISTANCE = 8.0;
    private static final double RETREAT_SPEED = 0.12;
    private static final double RETREAT_TRIGGER_DISTANCE = 4.5;
    private static final double MAX_FILM_DISTANCE = 7.0;
    private static final double NEAR_DISTANCE = 4.0;
    private static final double MIN_RETREATED_DISTANCE = 4.5;
    private static final double TARGET_HEALTH = 1000;
    private static final double TARGET_KNOCKBACK_RESISTANCE = 1;
    private static final double SEARCH_RADIUS = 40;
    private static final Set<String> REQUIRED_HAND_MERIDIANS =
            Set.of("left_lung_meridian", "right_lung_meridian");
    private static final MeridianOpening OPENED = MeridianOpening.newBuilder()
            .setStage(MeridianOpeningStage.OPENED)
            .setProgress(1)
            .build();

    @Override
    public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(WINDOW_WIDTH, WINDOW_HEIGHT);
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            int floor = prepareWorld(world);
            Scene scene = spawnScene(world, floor);
            context.waitFor(client -> client.level.getEntity(scene.camera().getId()) != null);
            setCamera(context, scene.camera());
            context.waitTicks(CAMERA_SETUP_TICKS);
            Film film = new Film(context, world, scene);
            film.capture(0, LEAD_IN_FRAME_COUNT);
            world.getServer().runOnServer(server -> {
                scene.mob().setNoGravity(false /* noGravity */);
                scene.mob().setOnGround(true /* onGround */);
                scene.mob().setNoAi(false /* aiDisabled */);
            });
            film.capture(LEAD_IN_FRAME_COUNT, FRAME_COUNT);
            film.verify();
        }
    }

    private static int prepareWorld(TestSingleplayerContext world) {
        world.getConnection().waitForChunksRender();
        var server = world.getServer();
        server.runCommand("weather clear");
        server.runCommand("time set noon");
        server.runCommand("gamemode spectator @a");
        int floor = SCENE_FLOOR_Y;
        server.runCommand("fill " + (-CLEAR_RADIUS) + " " + (floor - 1) + " " + (-CLEAR_RADIUS)
                + " " + CLEAR_RADIUS + " " + (floor - 1) + " " + CLEAR_RADIUS + " grass_block");
        server.runCommand("fill " + (-CLEAR_RADIUS) + " " + floor + " " + (-CLEAR_RADIUS)
                + " " + CLEAR_RADIUS + " " + (floor + CLEAR_HEIGHT) + " " + CLEAR_RADIUS + " air");
        return floor;
    }

    private static Scene spawnScene(TestSingleplayerContext world, int floor) {
        return world.getServer().computeOnServer(server -> {
            var level = server.overworld();
            Vindicator mob = EntityTypes.VINDICATOR.create(level, EntitySpawnReason.COMMAND);
            ((HumanoidEntityAccess) mob).meridian$skipVolumeBirth();
            mob.snapTo(MOB_X, floor, MOB_Z, 0, 0);
            mob.setNoAi(true /* aiDisabled */);
            mob.setNoGravity(true /* noGravity */);
            mob.setOnGround(true /* onGround */);
            mob.setPersistenceRequired();
            mob.setCustomName(Component.literal("卫道士"));
            mob.setCustomNameVisible(true);
            mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
            HumanoidSpeciesBirths.birth(mob, HumanoidTier.TRUE_QI);
            if (!level.addFreshEntity(mob)) {
                throw new AssertionError("卫道士没有加入客户端录像世界");
            }
            clearRandomTechniques(mob);
            TechniqueFabric.service().learn(mob.getUUID(), WAYFARER_PREFIX + "bow_upper");
            TechniqueFabric.service().learn(mob.getUUID(), WAYFARER_PREFIX + "repeater");
            prepareQi(mob);
            clearRandomTechniques(mob);
            TechniqueFabric.service().learn(mob.getUUID(), WAYFARER_PREFIX + "bow_upper");
            TechniqueFabric.service().learn(mob.getUUID(), WAYFARER_PREFIX + "repeater");
            int inserted = MobInventories.inventory(mob).insert(new ItemStack(Items.BOW));
            if (inserted != 1) {
                throw new AssertionError("卫道士背囊没有放入弓：" + inserted);
            }
            Villager target = target(level, floor);
            mob.setTarget(target);
            ArmorStand camera = camera(level, floor);
            return new Scene(mob, target, camera);
        });
    }

    private static void clearRandomTechniques(Mob mob) {
        var session = HumanoidTechniqueHost.prepare(mob);
        var body = session.body();
        var cleared = body.techniqueProgress().toBuilder().clearKnown().build();
        var current = body.currentBody();
        SetAddonProgress update = SetAddonProgress.newBuilder()
                .setId(TechniqueState.KEY)
                .setProgress(TechniqueState.pack(cleared))
                .build();
        BodyCommand command = BodyCommand.newBuilder()
                .setBodyId(current.id().value())
                .setExpectedRevision(current.revision())
                .setSetAddonProgress(update)
                .build();
        body.submitRecovery(UUID.randomUUID().toString(), command);
    }

    private static Villager target(net.minecraft.server.level.ServerLevel level, int floor) {
        Villager target = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
            target.snapTo(TARGET_X, floor, TARGET_Z, 180, 0);
        target.setNoAi(true /* aiDisabled */);
        target.setNoGravity(true /* noGravity */);
        target.setOnGround(true /* onGround */);
        target.setSilent(true /* silent */);
        target.setCustomName(Component.literal("目标村民"));
        target.setCustomNameVisible(true);
        target.setDeltaMovement(Vec3.ZERO);
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(TARGET_HEALTH);
        target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(TARGET_KNOCKBACK_RESISTANCE);
        target.setHealth((float) TARGET_HEALTH);
        if (!level.addFreshEntity(target)) {
            throw new AssertionError("目标没有加入客户端录像世界");
        }
        return target;
    }

    private static ArmorStand camera(net.minecraft.server.level.ServerLevel level, int floor) {
        ArmorStand camera = EntityTypes.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
        camera.snapTo(CAMERA_X, floor + CAMERA_EYE_HEIGHT, CAMERA_Z, 0, 0);
        camera.setYRot(CAMERA_YAW);
        camera.setXRot(CAMERA_PITCH);
        camera.setYHeadRot(CAMERA_YAW);
        camera.setInvisible(true /* invisible */);
        camera.setNoGravity(true /* noGravity */);
        if (!level.addFreshEntity(camera)) {
            throw new AssertionError("侧面机位没有加入客户端录像世界");
        }
        return camera;
    }

    private static void prepareQi(Mob mob) {
        var snapshot = HumanoidTechniqueHost.prepare(mob).body().snapshot().toBuilder();
        for (String meridian : REQUIRED_HAND_MERIDIANS) {
            openMeridian(snapshot, meridian);
        }
        restoreBody(mob, snapshot.build());
        DebugQi request = DebugQi.newBuilder()
                .setMode(DebugQiMode.FILL_LUMENS)
                .build();
        FailureCode result = HumanoidHealthHost.debugQi(mob, request);
        if (result != FailureCode.FAILURE_CODE_UNSPECIFIED) {
            throw new IllegalStateException("真元准备失败：" + result);
        }
        var body = BodyProtobuf.fromMessage(HumanoidTechniqueHost.prepare(mob).body().snapshot());
        TechniqueDefinition definition = definition(body, WAYFARER_PREFIX + "repeater");
        var held = HumanoidWayfarerWeapons.inspect(mob);
        var binding = TechniqueBinder.bind(body, definition, Hand.MAIN_HAND, held.getMainHandSide());
        if (!binding.hasBinding() || !TechniqueAvailability.affordable(body, definition, binding.getBinding())) {
            throw new IllegalStateException("客户端录像的连弩式无法绑定或支付");
        }
    }

    private static TechniqueDefinition definition(BodySnapshot body, String id) {
        TechniqueReference reference = TechniqueState.read(body).getKnownList().stream()
                .map(known -> known.getTechnique())
                .filter(known -> known.getId().equals(id))
                .findFirst()
                .orElseThrow();
        return TechniqueFabric.service().registry().find(reference).orElseThrow();
    }

    private static void openMeridian(BodyMessages.BodySnapshot.Builder snapshot, String id) {
        var state = snapshot.getMeridianStatesBuilderList().stream()
                .filter(meridian -> meridian.getId().equals(id))
                .findFirst().orElseThrow();
        if (state.getOpening().getStage() == MeridianOpeningStage.OPENED) {
            return;
        }
        var definition = snapshot.getStructureBuilder().getMeridiansBuilderList().stream()
                .filter(meridian -> meridian.getId().equals(id))
                .findFirst().orElseThrow();
        definition.setLumenVolume(definition.getLumenVolume() + HumanOpening.LUMEN_VOLUME_GAIN);
        state.setOpening(OPENED);
    }

    private static void restoreBody(Mob mob, BodyMessages.BodySnapshot snapshot) {
        HumanoidTechniqueHost.removed(mob);
        var saved = new HumanoidBodyState.SavedBody(mob.getUUID(), snapshot.toByteArray(), new byte[0][],
                HumanoidFabric.region(mob).sourceId());
        var species = HumanoidSpeciesHooks.claimed(mob).orElseThrow();
        var restored = HumanoidBodyState.restore(mob.getUUID(), saved, HumanoidFabric.now(), species);
        ((HumanoidEntityAccess) mob).meridian$humanoidBody(restored);
        HumanoidTechniqueHost.prepare(mob);
    }

    private static void setCamera(ClientGameTestContext context, ArmorStand camera) {
        context.runOnClient(client -> {
            Entity cameraEntity = client.level.getEntity(camera.getId());
            if (cameraEntity == null) {
                throw new AssertionError("客户端没有收到侧面机位");
            }
            client.options.setCameraType(CameraType.FIRST_PERSON);
            client.setCameraEntity(cameraEntity);
            client.gui.hud.getChat().clearMessages(false /* clearSentHistory */);
            client.gui.toastManager().clear();
        });
    }

    private record Scene(Vindicator mob, Villager target, ArmorStand camera) {
    }

    private static final class Film {
        private final ClientGameTestContext context;
        private final TestSingleplayerContext world;
        private final Scene scene;
        private final Set<UUID> arrows = new LinkedHashSet<>();
        private boolean heldBow;
        private boolean storedAxe;
        private boolean techniqueArrow;
        private boolean restoredAxe;
        private boolean targetApproaching;
        private boolean near;
        private boolean targetPassed;
        private boolean nearDiagnosticLogged;
        private boolean retreatDiagnosticLogged;
        private double maximumAfterNear;
        private final Set<String> casts = new LinkedHashSet<>();

        private Film(ClientGameTestContext context, TestSingleplayerContext world, Scene scene) {
            this.context = context;
            this.world = world;
            this.scene = scene;
        }

        private void capture(int firstFrame, int endFrame) {
            for (int frame = firstFrame; frame < endFrame; frame++) {
                observe(frame);
                context.takeScreenshot(TestScreenshotOptions.of(String.format(Locale.ROOT,
                        "vindicator-wayfarer-%03d", frame)).disableCounterPrefix());
                context.waitTicks(TICKS_PER_FRAME);
            }
        }

        private void observe(int frame) {
            world.getServer().runOnServer(server -> recordFrame(frame));
        }

        private void recordFrame(int frame) {
            Vindicator mob = scene.mob();
            Villager target = scene.target();
            observeCast(mob);
            observeEquipment(mob);
            observeArrows(mob);
            moveTarget(mob, target);
            double distance = observeDistance(mob, target);
            logFrame(frame, mob, target, distance);
        }

        private void observeCast(Vindicator mob) {
            var snapshot = ((HumanoidEntityAccess) mob).meridian$humanoidBody().snapshot();
            var progress = TechniqueState.read(BodyProtobuf.fromMessage(snapshot));
            if (progress.hasActiveCast()) {
                casts.add(progress.getActiveCast().getRequest().getTechnique().getId());
            }
        }

        private void observeEquipment(Vindicator mob) {
            var inventory = MobInventories.inventory(mob);
            var pack = inventory.snapshot();
            heldBow |= mob.getMainHandItem().is(Items.BOW);
            storedAxe |= pack.stream().anyMatch(stack -> stack.is(Items.IRON_AXE));
            restoredAxe |= mob.getMainHandItem().is(Items.IRON_AXE)
                    && pack.stream().anyMatch(stack -> stack.is(Items.BOW));
        }

        private void observeArrows(Vindicator mob) {
            var freshArrows = mob.level().getEntitiesOfClass(AbstractArrow.class,
                            mob.getBoundingBox().inflate(SEARCH_RADIUS), arrow -> arrow.getOwner() == mob).stream()
                    .filter(arrow -> arrows.add(arrow.getUUID()))
                    .toList();
            techniqueArrow |= freshArrows.stream().anyMatch(HumanTechniqueArrow.class::isInstance);
            if (techniqueArrow) {
                targetApproaching = true;
            }
        }

        private void moveTarget(Vindicator mob, Villager target) {
            if (targetApproaching && !targetPassed) {
                target.setPos(target.position().add(0, 0, -TARGET_APPROACH_SPEED));
                target.setDeltaMovement(new Vec3(0, 0, -TARGET_APPROACH_SPEED));
                double distance = mob.distanceTo(target);
                if (distance < NEAR_DISTANCE) {
                    near = true;
                }
                if (distance < RETREAT_TRIGGER_DISTANCE && distance < MAX_FILM_DISTANCE) {
                    mob.setPos(mob.position().add(0, 0, -RETREAT_SPEED));
                    mob.setDeltaMovement(new Vec3(0, 0, -RETREAT_SPEED));
                }
            } else {
                target.setDeltaMovement(Vec3.ZERO);
            }
        }

        private double observeDistance(Vindicator mob, Villager target) {
            double distance = mob.distanceTo(target);
            if (distance < NEAR_DISTANCE) {
                near = true;
            }
            if (near && target.position().z() <= mob.position().z() - TARGET_PASS_DISTANCE) {
                targetPassed = true;
            }
            if (near && !targetPassed) {
                maximumAfterNear = Math.max(maximumAfterNear, distance);
            }
            if (!nearDiagnosticLogged && distance < NEAR_DISTANCE) {
                nearDiagnosticLogged = true;
                logDistanceDiagnostic("近距", mob, target, distance);
            }
            if (!retreatDiagnosticLogged && distance < NEAR_DISTANCE - 1) {
                retreatDiagnosticLogged = true;
                logDistanceDiagnostic("退距", mob, target, distance);
            }
            return distance;
        }

        private void logFrame(int frame, Vindicator mob, Villager target, double distance) {
            LOGGER.info("卫道士录像 frame={} tick={} distance={} hand={} pack={} casts={} arrows={}",
                    frame, mob.tickCount, distance, mob.getMainHandItem(),
                    MobInventories.inventory(mob).snapshot(), casts, arrows.size());
        }

        private void logDistanceDiagnostic(String phase, Vindicator mob, Villager target, double distance) {
            LOGGER.info("卫道士客户端{}诊断 tick={} mob={} target={} distance={} onGround={} "
                            + "noGravity={} "
                            + "directive={} stance={} directing={} navigationDone={} navigationTarget={} pathTarget={} "
                            + "targetOnGround={} targetVelocity={}",
                    phase, mob.tickCount, mob.position(), target.position(), distance, mob.onGround(),
                    mob.isNoGravity(),
                    HumanoidBrain.directive(mob.getUUID()), HumanoidBrain.stance(mob.getUUID()),
                    HumanoidBrain.directing(mob), mob.getNavigation().isDone(), mob.getNavigation().getTargetPos(),
                    mob.getNavigation().getPath() == null ? "none" : mob.getNavigation().getPath().getTarget(),
                    target.onGround(), target.getDeltaMovement());
        }

        private void verify() {
            LOGGER.info("卫道士录像验收 heldBow={} storedAxe={} techniqueArrow={} restoredAxe={} near={}"
                    + " maximumAfterNear={} casts={} arrows={}", heldBow, storedAxe, techniqueArrow,
                    restoredAxe, near, maximumAfterNear, casts, arrows.size());
            if (!heldBow) {
                throw new AssertionError("录像中没有看见卫道士从背囊换出弓");
            }
            if (!storedAxe) {
                throw new AssertionError("录像中没有看见原斧进入背囊");
            }
            if (!casts.stream().anyMatch(id -> id.startsWith(WAYFARER_PREFIX))) {
                throw new AssertionError("录像中没有看见行矢式：" + casts);
            }
            if (!techniqueArrow) {
                throw new AssertionError("录像中没有看见带功法标记的箭");
            }
            if (!restoredAxe) {
                throw new AssertionError("录像结束时原斧没有还回手上");
            }
            if (maximumAfterNear < MIN_RETREATED_DISTANCE) {
                throw new AssertionError("目标逼近后卫道士没有退回射程：" + maximumAfterNear);
            }
        }
    }
}
