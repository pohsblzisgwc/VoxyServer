package com.dripps.voxyserver.mixin;

import com.dripps.voxyserver.sync.SectionDataAccess;

import me.cortex.voxy.common.world.*;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldEngine.class)
public abstract class WorldEngineObserverMixin {
    @Inject(
            method = "markDirty(Lme/cortex/voxy/common/world/WorldSection;II)V",
            at = @At("HEAD"),
            remap = false)
    private void voxyserver$changed(
            WorldSection section, int flags, int neighbors, CallbackInfo ci) {
        if ((flags & WorldEngine.UPDATE_TYPE_DONT_SAVE) == 0)
            ((com.dripps.voxyserver.sync.SectionPresence) (Object) section)
                    .voxyserver$setPresent(true);
        SectionDataAccess.changed((WorldEngine) (Object) this, section.key);
    }
}
