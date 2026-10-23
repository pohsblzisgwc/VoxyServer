package com.dripps.voxyserver.mixin;

import com.dripps.voxyserver.sync.SectionPresence;

import me.cortex.voxy.common.world.WorldSection;

import org.spongepowered.asm.mixin.*;

@Mixin(value = WorldSection.class, remap = false)
public abstract class WorldSectionPresenceMixin implements SectionPresence {
    @Unique private volatile boolean voxyserver$present;

    @Override
    public boolean voxyserver$isPresent() {
        return voxyserver$present;
    }

    @Override
    public void voxyserver$setPresent(boolean present) {
        voxyserver$present = present;
    }
}
