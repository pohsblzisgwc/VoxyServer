package com.dripps.voxyserver.client.mixin;

import com.dripps.voxyserver.client.ClientDemand;

import me.cortex.voxy.client.core.rendering.SectionUpdateRouter;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionUpdateRouter.class)
public abstract class SectionUpdateRouterMixin {
    @Inject(method = "watch(JI)Z", at = @At("RETURN"), remap = false)
    private void voxyserver$watch(long key, int types, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() && (types & 1) != 0) ClientDemand.watch(this, key);
    }

    @Inject(method = "unwatch(JI)Z", at = @At("RETURN"), remap = false)
    private void voxyserver$unwatch(long key, int types, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) ClientDemand.unwatch(this, key);
    }
}
