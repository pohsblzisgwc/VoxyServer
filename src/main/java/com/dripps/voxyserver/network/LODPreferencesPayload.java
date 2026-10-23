package com.dripps.voxyserver.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record LODPreferencesPayload(
        boolean enabled,
        int lodStreamRadius,
        int maxSectionsPerTick
) implements CustomPacketPayload {

    public static final int MAX_ALLOWED_RADIUS = 2048;
    public static final int MAX_ALLOWED_SECTIONS = 1024;

    public static final Type<LODPreferencesPayload> TYPE =
            new Type<>(Identifier.parse("voxyserver:lod_preferences"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LODPreferencesPayload> CODEC =
            StreamCodec.of(LODPreferencesPayload::write, LODPreferencesPayload::read);

    public LODPreferencesPayload {
        if (lodStreamRadius < 0 || lodStreamRadius > MAX_ALLOWED_RADIUS) {
            throw new IllegalArgumentException("invalid lodStreamRadius: " + lodStreamRadius
                    + ", must be between 0 and " + MAX_ALLOWED_RADIUS);
        }
        if (maxSectionsPerTick < 0 || maxSectionsPerTick > MAX_ALLOWED_SECTIONS) {
            throw new IllegalArgumentException("invalid maxSectionsPerTick: " + maxSectionsPerTick
                    + ", must be between 0 and " + MAX_ALLOWED_SECTIONS);
        }
    }

    private static void write(RegistryFriendlyByteBuf buf, LODPreferencesPayload payload) {
        buf.writeBoolean(payload.enabled);
        buf.writeVarInt(payload.lodStreamRadius);
        buf.writeVarInt(payload.maxSectionsPerTick);
    }

    private static LODPreferencesPayload read(RegistryFriendlyByteBuf buf) {
        boolean enabled = buf.readBoolean();
        int lodStreamRadius = buf.readVarInt();
        int maxSectionsPerTick = buf.readVarInt();
        if (lodStreamRadius < 0 || lodStreamRadius > MAX_ALLOWED_RADIUS
                || maxSectionsPerTick < 0 || maxSectionsPerTick > MAX_ALLOWED_SECTIONS) {
            throw new IllegalArgumentException("invalid preferences: radius=" + lodStreamRadius
                    + ", maxSections=" + maxSectionsPerTick);
        }
        return new LODPreferencesPayload(enabled, lodStreamRadius, maxSectionsPerTick);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
