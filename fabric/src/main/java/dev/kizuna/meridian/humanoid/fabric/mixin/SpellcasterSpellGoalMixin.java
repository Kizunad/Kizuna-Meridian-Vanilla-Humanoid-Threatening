package dev.kizuna.meridian.humanoid.fabric.mixin;

import dev.kizuna.meridian.humanoid.fabric.HumanoidEvokerSpells;
import net.minecraft.world.entity.monster.illager.SpellcasterIllager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 施功法期间原版法术目标不开始（C6c SPEC 4.4）；唤尖牙、唤恼鬼都走这一个 canUse，冷却不动。 */
@Mixin(targets = "net.minecraft.world.entity.monster.illager.SpellcasterIllager$SpellcasterUseSpellGoal")
public abstract class SpellcasterSpellGoalMixin {
    @Shadow @Final SpellcasterIllager this$0;

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void meridian$waitForTechnique(CallbackInfoReturnable<Boolean> callback) {
        // 有功法施放就不起原版法术，施放结束后原版照常判断。
        if (HumanoidEvokerSpells.blocksSpell(this$0)) {
            callback.setReturnValue(false);
        }
    }
}
