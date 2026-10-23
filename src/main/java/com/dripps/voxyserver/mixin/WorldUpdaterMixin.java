package com.dripps.voxyserver.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(WorldUpdater.class)
public abstract class WorldUpdaterMixin {
    @WrapMethod(method = "insertUpdate", remap = false)
    private static void voxyserver$serializeUpdates(
            WorldEngine world, VoxelizedSection data, Operation<Void> original) {
        synchronized (world) {
            original.call(world, data);
        }
    }
}
