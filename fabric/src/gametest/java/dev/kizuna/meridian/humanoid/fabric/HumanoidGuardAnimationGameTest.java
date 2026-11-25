package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EndActiveRequest;
import dev.kizuna.playeranimation.mobs.MobAnimationServer;
import dev.kizuna.playeranimation.protocol.pb.EntityAnimationProtocol.Play;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static dev.kizuna.meridian.humanoid.fabric.HumanoidGuardStaminaGameTest.frontHit;
import static dev.kizuna.meridian.humanoid.fabric.HumanoidGuardStaminaGameTest.raised;

/** 从 PA 的追踪补发表核对真实下发的守式片段和起播时钟。 */
public final class HumanoidGuardAnimationGameTest {
    private static final int GUARD_READY = 10;
    private static final int REFRESH_TICKS = 2;
    private static final int AFTER_RECOVERY = 20;
    private static final String WEAPON = "guard_weapon";
    private static final String ARMS = "guard_arms";

    @GameTest
    public void armedMobPlaysWeaponHoldHitAndLower(GameTestHelper test) {
        announce("armed_mob_plays_weapon_hold_hit_and_lower");
        var mob = raised(test, EntityTypes.VINDICATOR, new ItemStack(Items.IRON_AXE));
        verifyGuard(test, List.of(mob), WEAPON);
    }

    @GameTest
    public void emptyAndCrossbowHandsPlayArmClips(GameTestHelper test) {
        announce("empty_and_crossbow_hands_play_arm_clips");
        var unarmed = raised(test, EntityTypes.VINDICATOR, ItemStack.EMPTY);
        var crossbow = raised(test, EntityTypes.PILLAGER, new ItemStack(Items.CROSSBOW));
        crossbow.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.IRON_SWORD));
        verifyGuard(test, List.of(unarmed, crossbow), ARMS);
    }

    static void verifyGuard(GameTestHelper test, List<Mob> mobs, String style) {
        for (Mob mob : mobs) {
            assertClips(test, mob, style);
        }
        test.runAfterDelay(GUARD_READY, () -> {
            for (Mob mob : mobs) {
                frontHit(test, mob);
                assertClips(test, mob, style, style + "_hit");
                lower(test, mob);
                assertClips(test, mob, style + "_lower");
            }
        });
        test.runAfterDelay(AFTER_RECOVERY, () -> {
            for (Mob mob : mobs) {
                assertClips(test, mob);
            }
            test.succeed();
        });
    }

    @GameTest
    public void swappingAndBreakingWeaponKeepHoldClock(GameTestHelper test) {
        announce("swapping_and_breaking_weapon_keep_hold_clock");
        var mob = raised(test, EntityTypes.VINDICATOR, ItemStack.EMPTY);
        var original = assertClips(test, mob, ARMS).getFirst();
        var sword = new ItemStack(Items.GOLDEN_SWORD);
        sword.setDamageValue(sword.getMaxDamage() - 1);
        mob.setItemSlot(EquipmentSlot.MAINHAND, sword);
        test.runAfterDelay(REFRESH_TICKS, () -> {
            var armed = assertClips(test, mob, WEAPON).getFirst();
            assertClock(test, armed, original);
        });
        test.runAfterDelay(GUARD_READY, () -> {
            frontHit(test, mob);
            test.assertTrue(mob.getMainHandItem().isEmpty(), "该击应打碎主手金剑");
            var unarmed = assertClips(test, mob, ARMS, "guard_arms_hit").getFirst();
            assertClock(test, unarmed, original);
            lower(test, mob);
            assertClips(test, mob, "guard_arms_lower");
            test.succeed();
        });
    }

    @GameTest
    public void disarmedAfterContactStillPlaysWeaponHit(GameTestHelper test) {
        announce("disarmed_after_contact_still_plays_weapon_hit");
        var mob = raised(test, EntityTypes.VINDICATOR, new ItemStack(Items.IRON_AXE));
        // 直接伤害先提交身体和磨损，再记录攻击归属并通知宿主。在归属回调里缴械，区分命中主手与通知时主手。
        var attacker = new Cow(EntityTypes.COW, test.getLevel()) {
            @Override
            public void setLastHurtMob(Entity target) {
                super.setLastHurtMob(target);
                if (target == mob) {
                    mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                }
            }
        };
        test.runAfterDelay(GUARD_READY, () -> {
            frontHit(mob, attacker);
            test.assertTrue(mob.getMainHandItem().isEmpty(), "攻击归属回调应已缴械");
            assertClips(test, mob, ARMS, "guard_weapon_hit");
            lower(test, mob);
            assertClips(test, mob, "guard_arms_lower");
            test.succeed();
        });
    }

    @GameTest
    public void exhaustingHitUsesFrozenWeaponContact(GameTestHelper test) {
        announce("exhausting_hit_uses_frozen_weapon_contact");
        var mob = raised(test, EntityTypes.VINDICATOR, new ItemStack(Items.GOLDEN_AXE));
        test.runAfterDelay(GUARD_READY, () -> {
            var body = HumanoidTechniqueHost.prepare(mob).body();
            HumanoidGuardStaminaGameTest.spend(body, HumanoidGuardStaminaGameTest.available(body) - 1);
            frontHit(test, mob);
            test.assertTrue(body.techniqueProgress().getActiveCast().getSustained().getExhausted(),
                    "承击未触发精疲破防");
            mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        });
        test.runAfterDelay(GUARD_READY + REFRESH_TICKS, () -> assertClips(test, mob, "guard_weapon_hit"));
        test.runAfterDelay(AFTER_RECOVERY, () -> {
            assertClips(test, mob);
            test.succeed();
        });
    }

    private static void assertClock(GameTestHelper test, Play actual, Play original) {
        test.assertTrue(actual.getStartTick() == original.getStartTick()
                && actual.getPlaybackRate() == original.getPlaybackRate(), "换姿改变了起播时钟");
    }

    private static void lower(GameTestHelper test, Mob mob) {
        var service = TechniqueFabric.service();
        var id = mob.getUUID();
        var guard = service.activeGuard(id).orElseThrow();
        var end = EndActiveRequest.newBuilder()
                .setBodyId(guard.bodyId())
                .setCastId(guard.castId())
                .build();
        var outcome = service.endActive(id, end);
        test.assertTrue(outcome.getPhase() == CastPhase.RECOVERING, "撤防未进入收势");
    }

    private static List<Play> assertClips(GameTestHelper test, Mob mob, String... expected) {
        var plays = MobAnimationServer.snapshot(mob).stream().map(request -> request.getPlay()).toList();
        var actual = plays.stream().map(Play::getAnimationId).toList();
        var clips = List.of(expected);
        test.assertTrue(actual.equals(clips), "PA 预期 " + clips + "，实际 " + actual);
        return plays;
    }

    private static void announce(String name) {
        System.out.println("[GameTest] meridian_humanoid_test:humanoid_guard_animation_game_test_" + name);
    }
}
