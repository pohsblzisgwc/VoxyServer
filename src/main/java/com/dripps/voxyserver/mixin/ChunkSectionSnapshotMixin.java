package com.dripps.voxyserver.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

import org.spongepowered.asm.mixin.Mixin;

/**
 * A short section-local monitor permits safe background palette snapshots without world-thread
 * reads.
 */
@Mixin(LevelChunkSection.class)
public abstract class ChunkSectionSnapshotMixin {
    @WrapMethod(
            method =
                    "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;")
    private BlockState voxyserver$write(
            int x, int y, int z, BlockState state, boolean lock, Operation<BlockState> original) {
        synchronized (this) {
            return original.call(x, y, z, state, lock);
        }
    }

    @WrapMethod(method = "fillBiomesFromNoise")
    private void voxyserver$biomes(
            BiomeResolver resolver, int x, int y, int z, Operation<Void> original) {
        synchronized (this) {
            original.call(resolver, x, y, z);
        }
    }
}
