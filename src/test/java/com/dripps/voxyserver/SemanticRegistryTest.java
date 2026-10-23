package com.dripps.voxyserver;

import com.dripps.voxyserver.util.SemanticPalette;
import com.dripps.voxyserver.util.SemanticSectionHash;
import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;

/** Exercises the production resolver against real Minecraft registries with different ID orders. */
public final class SemanticRegistryTest {
    public static void runAllTests() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Biome plains = biome(0.8f), desert = biome(2.0f);
        var left = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        var right = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        register(left, "plains", plains); register(left, "desert", desert);
        register(right, "desert", desert); register(right, "plains", plains);
        left.freeze(); right.freeze();
        require(left.getId(plains) != right.getId(plains), "test registries must have different numeric IDs");

        var x = Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        var y = Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
        require(SemanticPalette.blockStateName(x).equals("minecraft:oak_log[axis=x]"), "block properties not canonical");
        int xId = Block.BLOCK_STATE_REGISTRY.getId(x), yId = Block.BLOCK_STATE_REGISTRY.getId(y);
        long[] leftPalette = SemanticPalette.resolve(new int[]{xId, yId},
                new int[]{left.getId(plains), left.getId(desert)}, new byte[]{15, (byte) 240}, left);
        long[] rightPalette = SemanticPalette.resolve(new int[]{yId, xId, xId},
                new int[]{right.getId(desert), right.getId(plains), right.getId(plains)},
                new byte[]{(byte) 240, 15, 15}, right);
        short[] a = new short[SemanticSectionHash.VOXELS], b = new short[a.length];
        for (int i = 0; i < a.length; i++) {
            a[i] = (short) (i & 1);
            b[i] = a[i] == 1 ? 0 : (short) ((i & 2) == 0 ? 1 : 2);
        }
        require(SemanticSectionHash.compute(leftPalette, a) == SemanticSectionHash.compute(rightPalette, b),
                "registry numbering, LUT order or duplicate entries changed the hash");
        long[] sameBinaryDifferentBiome = SemanticPalette.resolve(new int[]{xId}, new int[]{0}, new byte[]{15}, right);
        require(leftPalette[0] != sameBinaryDifferentBiome[0], "same binary ID hid different biome meaning");
        int air = Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());
        int caveAir = Block.BLOCK_STATE_REGISTRY.getId(Blocks.CAVE_AIR.defaultBlockState());
        long airLeft = SemanticPalette.resolve(new int[]{air}, new int[]{0}, new byte[]{15}, left)[0];
        long airRight = SemanticPalette.resolve(new int[]{caveAir}, new int[]{0}, new byte[]{15}, right)[0];
        require(airLeft == airRight, "air retained biome slot or air variant discarded by Voxy");
        require(airLeft != SemanticPalette.resolve(new int[]{air}, new int[]{0}, new byte[]{0}, right)[0],
                "air light differences must remain visible");
        System.out.println("PASS SemanticRegistryTest: actual block properties and reordered biome registries");
    }

    private static Biome biome(float temperature) {
        return new Biome.BiomeBuilder().hasPrecipitation(true).temperature(temperature).downfall(0.4f)
                .specialEffects(new BiomeSpecialEffects.Builder().waterColor(0x3f76e4).build())
                .mobSpawnSettings(MobSpawnSettings.EMPTY).generationSettings(BiomeGenerationSettings.EMPTY).build();
    }
    private static void register(MappedRegistry<Biome> registry, String name, Biome biome) {
        registry.register(ResourceKey.create(Registries.BIOME, Identifier.parse("minecraft:" + name)),
                biome, RegistrationInfo.BUILT_IN);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
