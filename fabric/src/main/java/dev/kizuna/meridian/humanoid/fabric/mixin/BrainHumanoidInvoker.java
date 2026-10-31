package dev.kizuna.meridian.humanoid.fabric.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 大脑指挥村民时只推进原版记忆与感知，任务由类人大脑代替。 */
@Mixin(Brain.class)
public interface BrainHumanoidInvoker {
    @Invoker("forgetOutdatedMemories")
    void meridian$tickMemories();

    @Invoker("tickSensors")
    void meridian$tickSensors(ServerLevel world, LivingEntity entity);
}
