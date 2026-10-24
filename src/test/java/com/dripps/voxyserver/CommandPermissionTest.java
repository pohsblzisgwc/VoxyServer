package com.dripps.voxyserver;

import com.dripps.voxyserver.server.VoxyServerCommands;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.SharedConstants;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.permissions.*;
import net.minecraft.world.phys.*;

public final class CommandPermissionTest {
    public static void runAllTests() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        VoxyServerCommands.register(dispatcher, new Voxyserver());
        String[] commands = {
            "voxyserver",
            "voxyserver help",
            "voxyserver enabled",
            "voxyserver enabled false",
            "voxyserver enabled true",
            "voxyserver config set enabled false",
            "voxyserver reload",
            "voxyserver config list",
            "voxyserver config reset",
            "voxyserver config reset maintenanceMillisPerSecond",
            "voxyserver config get sourceColumnRecheckSeconds",
            "voxyserver config set sourceColumnRecheckSeconds 600",
            "voxyserver config reload",
            "voxyserver radius",
            "voxyserver radius 4096",
            "voxyserver bandwidth 0",
            "voxyserver threads 65",
            "voxyserver threads 10000",
            "voxyserver import existing all",
            "voxyserver import existing all force",
            "voxyserver import existing current",
            "voxyserver stats",
            "voxyserver stats continuous",
            "voxyserver stats stop"
        };
        for (PermissionLevel level : PermissionLevel.values()) {
            var source =
                    new CommandSourceStack(
                            CommandSource.NULL,
                            Vec3.ZERO,
                            Vec2.ZERO,
                            null,
                            LevelBasedPermissionSet.forLevel(level),
                            Component.literal("permission-test"),
                            null);
            boolean admin = level.isEqualOrHigherThan(PermissionLevel.ADMINS);
            require(
                    VoxyServerCommands.isAdministrator(source) == admin,
                    "incorrect administrator classification: " + level);
            for (String command : commands) {
                var parsed = dispatcher.parse(command, source);
                if (admin)
                    require(
                            !parsed.getReader().canRead()
                                    && parsed.getContext().getCommand() != null,
                            "admin command rejected: " + command);
                else {
                    try {
                        dispatcher.execute(parsed);
                        throw new AssertionError("unauthorized command executed: " + command);
                    } catch (CommandSyntaxException expected) {
                    }
                }
            }
            var root = dispatcher.getRoot().getChild("voxyserver");
            require(root.canUse(source) == admin, "root command permission mismatch");
        }
        System.out.println(
                "PASS CommandPermissionTest: registered Brigadier tree denies non-admin queries,"
                        + " changes, import and stats; admin/owner high-limit commands accepted");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
