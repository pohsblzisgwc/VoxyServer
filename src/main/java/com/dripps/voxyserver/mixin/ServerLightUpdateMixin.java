package com.dripps.voxyserver.mixin;

import com.dripps.voxyserver.server.DirtyTracker;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lighting may change outside the source block's section. Coalesce it without reading world data
 * here.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerLightUpdateMixin {
    @Shadow
    public abstract net.minecraft.world.level.Level getLevel();

    @Inject(method = "onLightUpdate", at = @At("HEAD"))
    private void voxyserver$light(LightLayer layer, SectionPos pos, CallbackInfo ci) {
        var level = (ServerLevel) getLevel();
        int y = pos.y() << 4;
        if (y >= level.getMinY() && y < level.getMaxY() && DirtyTracker.INSTANCE != null)
            DirtyTracker.INSTANCE.markDirty(level, pos.x(), y, pos.z());
    }
}
