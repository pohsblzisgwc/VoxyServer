package com.dripps.voxyserver.mixin;

import com.dripps.voxyserver.sync.SectionPresence;
import com.llamalad7.mixinextras.injector.wrapoperation.*;

import me.cortex.voxy.common.world.*;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = ActiveSectionTracker.class, remap = false)
public abstract class SectionLoadPresenceMixin {
    @WrapOperation(
            method = "acquire(JZ)Lme/cortex/voxy/common/world/WorldSection;",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lme/cortex/voxy/common/world/ActiveSectionTracker$SectionLoader;load(Lme/cortex/voxy/common/world/WorldSection;)I"))
    private int voxyserver$load(
            ActiveSectionTracker.SectionLoader loader,
            WorldSection section,
            Operation<Integer> original) {
        int status = original.call(loader, section);
        ((SectionPresence) (Object) section).voxyserver$setPresent(status == 0);
        return status;
    }
}
