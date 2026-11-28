package dev.kizuna.meridian.humanoid.fabric.mixin;

import dev.kizuna.meridian.humanoid.fabric.DonationChunkSave;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityStorage.class)
abstract class DonationStorageMixin {
    @Shadow @Final private SimpleRegionStorage simpleRegionStorage;

    @Inject(method = "reportSaveFailureIfPresent", at = @At("HEAD"))
    private void meridian$captureWrite(CompletableFuture<?> write, ChunkPos chunk, CallbackInfo callback) {
        DonationChunkSave.capture(chunk, write, simpleRegionStorage);
    }
}
