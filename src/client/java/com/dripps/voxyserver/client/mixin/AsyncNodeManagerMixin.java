package com.dripps.voxyserver.client.mixin;

import com.dripps.voxyserver.client.ClientDemand;

import me.cortex.voxy.client.core.rendering.SectionUpdateRouter;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AsyncNodeManager.class)
public abstract class AsyncNodeManagerMixin {
    @Shadow @Final private SectionUpdateRouter router;

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void voxyserver$bind(
            int max, IGeometryData geometry, RenderGenerationService service, CallbackInfo ci) {
        ClientDemand.bindRouter(router, ((RenderWorldAccessor) service).voxyserver$world());
    }
}
