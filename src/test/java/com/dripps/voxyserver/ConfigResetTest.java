package com.dripps.voxyserver;

import com.dripps.voxyserver.config.VoxyServerConfig;
import com.dripps.voxyserver.server.VoxyServerCommands;
import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.phys.*;

import java.util.List;

public final class ConfigResetTest {
    private static final class Mod extends Voxyserver {
        VoxyServerConfig applied;

        @Override
        public List<String> applyConfigFromCommand(VoxyServerConfig c) {
            c.requireValid();
            applied = c;
            return List.of();
        }
    }

    public static void runAllTests() throws Exception {
        var field = Voxyserver.class.getDeclaredField("config");
        field.setAccessible(true);
        var saved = field.get(null);
        try {
            var current = new VoxyServerConfig();
            current.maintenanceMillisPerSecond = 200;
            current.maxBandwidthBytesPerSec = 0;
            current.backgroundGenerationChunksPerMinute = 600000;
            field.set(null, current);
            var mod = new Mod();
            var dispatcher = new CommandDispatcher<CommandSourceStack>();
            VoxyServerCommands.register(dispatcher, mod);
            var source =
                    new CommandSourceStack(
                            CommandSource.NULL,
                            Vec3.ZERO,
                            Vec2.ZERO,
                            null,
                            LevelBasedPermissionSet.ADMIN,
                            Component.literal("reset-test"),
                            null);
            require(
                    dispatcher.execute("voxyserver config reset maintenanceMillisPerSecond", source)
                            == 1,
                    "single reset command rejected");
            require(
                    mod.applied.maintenanceMillisPerSecond == 20
                            && mod.applied.maxBandwidthBytesPerSec == 0
                            && mod.applied.backgroundGenerationChunksPerMinute == 600000,
                    "single reset changed unrelated values");
            require(
                    current.maintenanceMillisPerSecond == 200,
                    "reset mutated current configuration before application");
            mod.applied = null;
            require(
                    dispatcher.execute("voxyserver config reset missingKey", source) == 0
                            && mod.applied == null,
                    "unknown key changed configuration");
            require(
                    dispatcher.execute(
                                            "voxyserver config reset"
                                                + " MAX_BACKGROUND_CHUNKS_PER_MINUTE",
                                            source)
                                    == 0
                            && mod.applied == null,
                    "constant accepted as config key");
            current.maxClientVoxyRadius = 2048;
            current.lodStreamRadius = 2048;
            dispatcher.execute("voxyserver config reset lodStreamRadius", source);
            require(
                    mod.applied.lodStreamRadius == 1024 && mod.applied.maxClientVoxyRadius == 1024,
                    "radius aliases desynchronized");
            current.backgroundGenerationStartMspt = 100;
            current.backgroundGenerationStopMspt = 200;
            mod.applied = null;
            require(
                    dispatcher.execute(
                                            "voxyserver config reset backgroundGenerationStopMspt",
                                            source)
                                    == 0
                            && mod.applied == null,
                    "cross-field invalid reset applied");
            require(
                    dispatcher.execute("voxyserver config reset", source) == 1,
                    "full reset command failed");
            var defaults = new VoxyServerConfig();
            for (var f : VoxyServerConfig.class.getFields())
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                    require(
                            java.util.Objects.equals(f.get(mod.applied), f.get(defaults)),
                            "full reset omitted " + f.getName());
            require(
                    mod.applied.enabled
                            && !mod.applied.backgroundGenerationEnabled
                            && mod.applied.backgroundGenerationChunksPerMinute == 6,
                    "reset defaults incorrect");
            System.out.println(
                    "PASS ConfigResetTest: actual administrator commands, single/all defaults,"
                        + " aliases, invalid keys, cross-field validation and unchanged original"
                        + " config");
        } finally {
            field.set(null, saved);
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
