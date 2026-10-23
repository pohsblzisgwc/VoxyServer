package com.dripps.voxyserver.client.mixin;

import com.dripps.voxyserver.client.ClientDemand;

import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.common.world.WorldEngine;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderGenerationService.class)
public abstract class RenderGenerationServiceMixin {
    @Shadow @Final private WorldEngine world;

    @Inject(method = "enqueueTask", at = @At("HEAD"), cancellable = true, remap = false)
    private void voxyserver$demand(long key, CallbackInfo ci) {
        if (ClientDemand.defer(
                world, key, () -> ((RenderGenerationService) (Object) this).enqueueTask(key)))
            ci.cancel();
    }
}
