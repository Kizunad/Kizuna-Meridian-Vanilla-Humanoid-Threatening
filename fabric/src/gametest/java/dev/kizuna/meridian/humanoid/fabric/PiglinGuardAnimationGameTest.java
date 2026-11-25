package dev.kizuna.meridian.humanoid.fabric;

import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static dev.kizuna.meridian.humanoid.fabric.HumanoidGuardStaminaGameTest.raised;

/** 猪灵附属提供类人模板组成员，生人宿主交付格挡动作。 */
public final class PiglinGuardAnimationGameTest {
    @GameTest
    public void piglinAndBruteUseGoldWeaponClips(GameTestHelper test) {
        System.out.println("[GameTest] meridian_humanoid_test:piglin_guard_animation_game_test_"
                + "piglin_and_brute_use_gold_weapon_clips");
        var piglin = raised(test, EntityTypes.PIGLIN, new ItemStack(Items.GOLDEN_SWORD));
        var brute = raised(test, EntityTypes.PIGLIN_BRUTE, new ItemStack(Items.GOLDEN_AXE));
        test.assertTrue(!HumanoidBrain.managed(piglin) && !HumanoidBrain.managed(brute), "应保留原版 Brain");
        HumanoidGuardAnimationGameTest.verifyGuard(test, List.of(piglin, brute), "guard_weapon");
    }
}
