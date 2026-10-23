package com.dripps.voxyserver.client.mixin;

import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.common.world.WorldEngine;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderGenerationService.class)
public interface RenderWorldAccessor {
    @Accessor(value = "world", remap = false)
    WorldEngine voxyserver$world();
}
