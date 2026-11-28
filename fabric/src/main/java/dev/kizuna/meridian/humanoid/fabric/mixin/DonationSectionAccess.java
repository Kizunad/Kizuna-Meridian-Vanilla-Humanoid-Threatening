package dev.kizuna.meridian.humanoid.fabric.mixin;

import java.util.function.Consumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PersistentEntitySectionManager.class)
public interface DonationSectionAccess<T extends EntityAccess> {
    @Invoker("storeChunkSections")
    boolean meridian$storeChunkSections(long chunk, Consumer<T> afterSave);
}
