package com.dripps.voxyserver.sync;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

/**
 * Native voxel updates and remote snapshots use the same world monitor, never the Minecraft tick
 * monitor.
 */
public final class SectionDataAccess {
    public static volatile java.util.function.BiConsumer<WorldEngine, Long> observer;

    public static void changed(WorldEngine world, long key) {
        var callback = observer;
        if (callback != null) callback.accept(world, key);
    }

    private SectionDataAccess() {}

    public static WorldSection acquireStored(WorldEngine world, long key) {
        WorldSection section = world.acquireIfExists(key);
        if (section != null && !((SectionPresence) (Object) section).voxyserver$isPresent()) {
            section.release();
            return null;
        }
        return section;
    }

    public record Snapshot(long[] data, byte children) {}

    public static Snapshot snapshot(WorldEngine world, WorldSection section) {
        synchronized (world) {
            return new Snapshot(section.copyData(), section.getNonEmptyChildren());
        }
    }
}
