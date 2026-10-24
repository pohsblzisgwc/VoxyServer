package com.dripps.voxyserver.mixin;

import com.dripps.voxyserver.server.DirtyTracker;

import net.minecraft.server.level.*;
import net.minecraft.world.level.chunk.ChunkAccess;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ChunkMap.class)
public abstract class ServerBiomeUpdateMixin {
    @Shadow @Final private ServerLevel level;

    @Inject(method = "resendBiomesForChunks", at = @At("HEAD"))
    private void voxyserver$biomes(List<ChunkAccess> chunks, CallbackInfo ci) {
        var tracker = DirtyTracker.INSTANCE;
        if (tracker == null) return;
        for (var chunk : chunks)
            for (int y = chunk.getMinSectionY();
                    y < chunk.getMinSectionY() + chunk.getSections().length;
                    y++) tracker.markDirty(level, chunk.getPos().x(), y << 4, chunk.getPos().z());
    }
}
