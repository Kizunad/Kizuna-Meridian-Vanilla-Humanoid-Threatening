package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import java.util.Locale;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 生存玩家正面接敌。逐 tick 拍摄，施放请求只由卫道士的 serverAiStep 发出。 */
public final class HumanoidGuardClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_humanoid_guard_film");
    private static final int WIDTH = 960;
    private static final int HEIGHT = 540;
    private static final int FRAMES = 52;
    private static final int HIT_FRAME = 23;
    private static final int CLIENT_SETTLE = 2;
    private static final long ROLL_SEED = 4112;
    private static final double CENTER = 0.5;
    private static final double PLAYER_Z = 3.25;
    private static final int CLEAR_HEIGHT = 4;

    @Override
    public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(WIDTH, HEIGHT);
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            var server = world.getServer();
            server.runCommand("weather clear");
            server.runCommand("time set noon");
            server.runCommand("difficulty normal");
            server.runCommand("gamemode survival @a");
            int floor = server.computeOnServer(host ->
                    host.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0));
            server.runCommand("fill -4 " + (floor - 1) + " -4 4 " + (floor - 1) + " 6 grass_block");
            server.runCommand("fill -4 " + floor + " -4 4 " + (floor + CLEAR_HEIGHT) + " 6 air");
            server.runCommand("tp @a " + CENTER + " " + floor + " " + PLAYER_Z + " 180 6");
            context.waitTicks(FRAMES);
            world.getConnection().waitForChunksRender();
            server.runCommand("tick freeze");
            var defender = defender(world, floor);
            film(context, world, defender);
            server.runCommand("tick unfreeze");
        }
    }

    private static Vindicator defender(TestSingleplayerContext world, int floor) {
        return world.getServer().computeOnServer(server -> {
            var level = server.overworld();
            var mob = EntityTypes.VINDICATOR.create(level, EntitySpawnReason.COMMAND);
            mob.setUUID(new UUID(level.getSeed() ^ ROLL_SEED, 0));
            mob.snapTo(CENTER, floor, CENTER, 0, 0);
            mob.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
            mob.setPersistenceRequired();
            HumanoidSpeciesBirths.birth(mob, HumanoidTier.MORTAL);
            mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            if (!level.addFreshEntity(mob)) {
                throw new AssertionError("卫道士未入场");
            }
            mob.setTarget(server.getPlayerList().getPlayers().getFirst());
            return mob;
        });
    }

    private static void film(ClientGameTestContext context, TestSingleplayerContext world, Vindicator mob) {
        context.runOnClient(client -> {
            client.gui.hud.getChat().clearMessages(false /* clearSentHistory */);
            client.gui.toastManager().clear();
        });
        for (int frame = 0; frame < FRAMES; frame++) {
            if (frame < HIT_FRAME) {
                world.getServer().runOnServer(server -> {
                    var player = server.getPlayerList().getPlayers().getFirst();
                    player.swing(InteractionHand.MAIN_HAND);
                });
            }
            if (frame == HIT_FRAME) {
                world.getServer().runOnServer(server -> {
                    if (TechniqueFabric.service().activeGuard(mob.getUUID()).isEmpty()) {
                        var guard = HumanoidBrain.guard(mob);
                        throw new AssertionError("玩家出拳前卫道士未自行成架，准入 " + guard.checks()
                                + " 候选 " + guard.candidates() + " 请求 " + guard.requests());
                    }
                });
                context.runOnClient(client -> {
                    var target = client.level.getEntity(mob.getId());
                    client.gameMode.attack(client.player, target);
                    client.player.swing(InteractionHand.MAIN_HAND);
                });
            }
            world.getServer().runCommand("tick step 1");
            context.waitTicks(CLIENT_SETTLE);
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                var guard = HumanoidBrain.guard(mob);
                LOGGER.info("自守录像 tick={} 挥击={} 方向={} 距离={} 朝向={} 候选={} 请求={}",
                        server.overworld().getGameTime(), player.swinging, player.getLookAngle(),
                        player.distanceTo(mob), mob.getYRot(), guard.candidates(), guard.requests());
            });
            var name = String.format(Locale.ROOT, "guard-%03d", frame);
            context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix());
        }
    }
}
