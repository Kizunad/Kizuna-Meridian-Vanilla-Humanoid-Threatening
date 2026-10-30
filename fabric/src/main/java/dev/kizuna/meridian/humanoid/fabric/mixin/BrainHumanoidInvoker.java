package dev.kizuna.meridian.humanoid.fabric.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.Brain;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 大脑指挥村民时只推进原版记忆与感知，任务由类人大脑代替。 */
@Mixin(Brain.class)
public interface BrainHumanoidInvoker {
    @Invoker("tickMemories")
    void meridian$tickMemories();

    @Invoker("tickSensors")
    void meridian$tickSensors(ServerWorld world, LivingEntity entity);
}
