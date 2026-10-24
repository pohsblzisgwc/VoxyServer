package com.dripps.voxyserver.server;

import com.dripps.voxyserver.Voxyserver;
import com.dripps.voxyserver.config.VoxyServerConfig;
import com.dripps.voxyserver.util.ServerStatsTracker;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class VoxyServerCommands {
    private VoxyServerCommands() {
    }

    /** Server-authoritative gate for the complete command tree, including help, reads and stats. */
    public static boolean isAdministrator(CommandSourceStack source) {
        return source.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, Voxyserver mod) {
        Supplier<WorldImportCoordinator> coordinatorSupplier = mod::getImportCoordinator;
        dispatcher.register(
                Commands.literal("voxyserver")
                        .requires(VoxyServerCommands::isAdministrator)
                        .executes(context -> showHelp(context.getSource()))
                        .then(Commands.literal("enabled")
                                .executes(context -> configGet(context.getSource(),"enabled"))
                                .then(Commands.argument("value",BoolArgumentType.bool())
                                        .executes(context -> configSet(context.getSource(),mod,"enabled",String.valueOf(BoolArgumentType.getBool(context,"value"))))))
                        .then(Commands.literal("help")
                                .executes(context -> showHelp(context.getSource())))
                        .then(Commands.literal("reload")
                                .executes(context -> configReload(context.getSource(), mod)))
                        .then(Commands.literal("radius")
                                .executes(context -> getRadius(context.getSource()))
                                .then(Commands.argument("chunks", IntegerArgumentType.integer(1, 4096))
                                        .executes(context -> setRadius(context.getSource(), mod, IntegerArgumentType.getInteger(context, "chunks")))))
                        .then(Commands.literal("bandwidth")
                                .executes(context -> getBandwidth(context.getSource()))
                                .then(Commands.argument("bytesPerSec", IntegerArgumentType.integer(0))
                                        .executes(context -> setBandwidth(context.getSource(), mod, IntegerArgumentType.getInteger(context, "bytesPerSec")))))
                        .then(Commands.literal("threads")
                                .executes(context -> getThreads(context.getSource()))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, VoxyServerConfig.MAX_NATIVE_WORKER_THREADS))
                                        .executes(context -> setThreads(context.getSource(), mod, IntegerArgumentType.getInteger(context, "count")))))
                        .then(Commands.literal("hashsync")
                                .executes(context -> getHashSync(context.getSource()))
                                .then(Commands.argument("enabled", BoolArgumentType.bool())
                                        .executes(context -> setHashSync(context.getSource(), mod, BoolArgumentType.getBool(context, "enabled")))))
                        .then(Commands.literal("pacing")
                                .executes(context -> getPacing(context.getSource()))
                                .then(Commands.argument("enabled", BoolArgumentType.bool())
                                        .executes(context -> setPacing(context.getSource(), mod, BoolArgumentType.getBool(context, "enabled")))))
                        .then(Commands.literal("regionhash")
                                .executes(context -> getRegionHash(context.getSource()))
                                .then(Commands.argument("enabled", BoolArgumentType.bool())
                                        .executes(context -> setRegionHash(context.getSource(), mod, BoolArgumentType.getBool(context, "enabled")))))
                        .then(Commands.literal("config")
                                .then(Commands.literal("reset")
                                        .executes(context -> configReset(context.getSource(),mod,null))
                                        .then(Commands.argument("key",StringArgumentType.string())
                                                .suggests(KEY_SUGGEST)
                                                .executes(context -> configReset(context.getSource(),mod,StringArgumentType.getString(context,"key")))))
                                .then(Commands.literal("list")
                                        .executes(context -> configList(context.getSource())))
                                .then(Commands.literal("reload")
                                        .executes(context -> configReload(context.getSource(), mod)))
                                .then(Commands.literal("get")
                                        .then(Commands.argument("key", StringArgumentType.string())
                                                .suggests(KEY_SUGGEST)
                                                .executes(context -> configGet(context.getSource(),
                                                        StringArgumentType.getString(context, "key")))))
                                .then(Commands.literal("set")
                                        .then(Commands.argument("key", StringArgumentType.string())
                                                .suggests(KEY_SUGGEST)
                                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                                        .executes(context -> configSet(context.getSource(), mod,
                                                                StringArgumentType.getString(context, "key"),
                                                                StringArgumentType.getString(context, "value")))))))
                        .then(Commands.literal("import")
                                .then(Commands.literal("existing")
                                        .then(Commands.literal("all")
                                                .executes(context -> executeAll(context.getSource(), coordinatorSupplier, false))
                                                .then(Commands.literal("force")
                                                        .executes(context -> executeAll(context.getSource(), coordinatorSupplier, true))))
                                        .then(Commands.literal("current")
                                                .executes(context -> executeCurrent(context.getSource(), coordinatorSupplier, false))
                                                .then(Commands.literal("force")
                                                        .executes(context -> executeCurrent(context.getSource(), coordinatorSupplier, true))))
                                        .then(Commands.literal("dimension")
                                                .then(Commands.argument("dimension", IdentifierArgument.id())
                                                        .suggests((context, builder) -> {
                                                            java.util.List<String> dimensions = new java.util.ArrayList<>();
                                                            for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
                                                                dimensions.add(level.dimension().identifier().toString());
                                                            }
                                                            return SharedSuggestionProvider.suggest(dimensions, builder);
                                                        })
                                                        .executes(context -> executeDimension(
                                                                context.getSource(),
                                                                coordinatorSupplier,
                                                                IdentifierArgument.getId(context, "dimension").toString(), false
                                                        ))
                                                        .then(Commands.literal("force")
                                                                .executes(context -> executeDimension(context.getSource(), coordinatorSupplier,
                                                                        IdentifierArgument.getId(context, "dimension").toString(), true)))))
                                        .then(Commands.literal("status")
                                                .executes(context -> executeStatus(context.getSource(), coordinatorSupplier)))
                                        .then(Commands.literal("cancel")
                                                .executes(context -> executeCancel(context.getSource(), coordinatorSupplier)))))
                        .then(Commands.literal("stats")
                                .executes(context -> statsOnce(context.getSource(), mod))
                                .then(Commands.literal("continuous")
                                        .executes(context -> statsContinuous(context.getSource(), mod)))
                                .then(Commands.literal("stop")
                                        .executes(context -> statsStop(context.getSource(), mod))))
        );
    }

    private static final SuggestionProvider<CommandSourceStack> KEY_SUGGEST =
            (context, builder) -> SharedSuggestionProvider.suggest(configKeys(), builder);

    private static final String COORDINATOR_NOT_READY = "import coordinator is not ready";

    private static int executeAll(CommandSourceStack source, Supplier<WorldImportCoordinator> coordinatorSupplier, boolean force) {
        WorldImportCoordinator coordinator = coordinatorSupplier.get();
        if (coordinator == null) {
            source.sendFailure(Component.literal(COORDINATOR_NOT_READY));
            return 0;
        }
        return coordinator.startAll(source, force) ? 1 : 0;
    }

    private static int executeCurrent(CommandSourceStack source, Supplier<WorldImportCoordinator> coordinatorSupplier, boolean force) {
        WorldImportCoordinator coordinator = coordinatorSupplier.get();
        if (coordinator == null) {
            source.sendFailure(Component.literal(COORDINATOR_NOT_READY));
            return 0;
        }
        return coordinator.startCurrent(source, force) ? 1 : 0;
    }

    private static int executeDimension(CommandSourceStack source, Supplier<WorldImportCoordinator> coordinatorSupplier, String dimensionId, boolean force) {
        WorldImportCoordinator coordinator = coordinatorSupplier.get();
        if (coordinator == null) {
            source.sendFailure(Component.literal(COORDINATOR_NOT_READY));
            return 0;
        }

        ServerLevel level = findLevel(source, dimensionId);
        if (level == null) {
            source.sendFailure(Component.literal("dunno dimension: " + dimensionId));
            return 0;
        }
        return coordinator.startDimension(source, level, force) ? 1 : 0;
    }

    private static int executeStatus(CommandSourceStack source, Supplier<WorldImportCoordinator> coordinatorSupplier) {
        WorldImportCoordinator coordinator = coordinatorSupplier.get();
        if (coordinator == null) {
            source.sendFailure(Component.literal(COORDINATOR_NOT_READY));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(coordinator.getStatusSummary()), false);
        return 1;
    }

    private static int executeCancel(CommandSourceStack source, Supplier<WorldImportCoordinator> coordinatorSupplier) {
        WorldImportCoordinator coordinator = coordinatorSupplier.get();
        if (coordinator == null) {
            source.sendFailure(Component.literal(COORDINATOR_NOT_READY));
            return 0;
        }
        return coordinator.cancel(source) ? 1 : 0;
    }

    private static ServerLevel findLevel(CommandSourceStack source, String dimensionId) {
        for (ServerLevel level : source.getServer().getAllLevels()) {
            if (level.dimension().identifier().toString().equals(dimensionId)) {
                return level;
            }
        }
        return null;
    }

    private static List<String> configKeys() {
        List<String> keys = new ArrayList<>();
        for (Field f : VoxyServerConfig.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            Class<?> t = f.getType();
            if (com.dripps.voxyserver.config.ConfigValueParser.supports(t)) keys.add(f.getName());
        }
        return keys;
    }

    private static Field findConfigField(String key) {
        for (Field f : VoxyServerConfig.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (!f.getName().equals(key)) continue;
            Class<?> t = f.getType();
            if (com.dripps.voxyserver.config.ConfigValueParser.supports(t)) return f;
        }
        return null;
    }

    private static boolean isLiveReloadable(String key) {
        return !key.equals("generateOnChunkLoad")
                && !key.equals("generateOnChunkUnload")
                && !key.equals("dirtyTrackingEnabled");
    }

    private static int configList(CommandSourceStack source) {
        VoxyServerConfig cfg = Voxyserver.getConfig();
        if (cfg == null) {
            source.sendFailure(Component.literal("config not loaded"));
            return 0;
        }
        MutableComponent out = block("config");
        try {
            for (String key : configKeys()) {
                Field f = findConfigField(key);
                Object val = f.get(cfg);
                boolean live = isLiveReloadable(key);
                out.append(Component.literal("\n"))
                        .append(Component.literal("  " + key + " = ").withStyle(ChatFormatting.AQUA))
                        .append(Component.literal(String.valueOf(val)).withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(live ? "  [live]" : "  [restart required]")
                                .withStyle(live ? ChatFormatting.DARK_GREEN : ChatFormatting.RED));
            }
        } catch (IllegalAccessException e) {
            source.sendFailure(Component.literal("failed to read config"));
            return 0;
        }
        source.sendSuccess(() -> out, false);
        return 1;
    }

    private static int configGet(CommandSourceStack source, String key) {
        VoxyServerConfig cfg = Voxyserver.getConfig();
        Field f = findConfigField(key);
        if (cfg == null || f == null) {
            source.sendFailure(Component.literal("unknown key: " + key + ". valid: " + String.join(", ", configKeys())));
            return 0;
        }
        Object val;
        try {
            val = f.get(cfg);
        } catch (IllegalAccessException e) {
            source.sendFailure(Component.literal("failed to read " + key));
            return 0;
        }
        boolean live = isLiveReloadable(key);
        MutableComponent line = kv(key, val, ChatFormatting.WHITE)
                .append(Component.literal(live ? "  [live]" : "  [restart required]")
                        .withStyle(live ? ChatFormatting.DARK_GREEN : ChatFormatting.RED));
        source.sendSuccess(() -> line, false);
        return 1;
    }

    private static int configReset(CommandSourceStack source,Voxyserver mod,String key) {
        var defaults=new VoxyServerConfig();
        var current=Voxyserver.getConfig();
        if(current==null){source.sendFailure(Component.literal("config not loaded"));return 0;}
        var candidate=defaults;
        if(key!=null) {
            Field field=findConfigField(key);
            if(field==null){source.sendFailure(Component.literal("unknown key: "+key));return 0;}
            candidate=current.copy();
            try{field.set(candidate,field.get(defaults));}catch(IllegalAccessException failure){throw new IllegalStateException(failure);}
            if(key.equals("maxClientVoxyRadius") || key.equals("lodStreamRadius")) {
                candidate.maxClientVoxyRadius=defaults.maxClientVoxyRadius;
                candidate.lodStreamRadius=defaults.lodStreamRadius;
            }
        }
        try {
            candidate.requireValid();
            var notes=mod.applyConfigFromCommand(candidate);
            source.sendSuccess(()->Component.literal("[VoxyServer] restored "+(key==null?"all server settings":key)+" to defaults (saved & applied)"),true);
            for(var note:notes)source.sendSuccess(()->Component.literal("note: "+note),false);
            return 1;
        } catch(IllegalArgumentException invalid) {
            source.sendFailure(Component.literal("reset rejected: "+invalid.getMessage()));return 0;
        }
    }

    private static int configSet(CommandSourceStack source, Voxyserver mod, String key, String rawValue) {
        VoxyServerConfig current = Voxyserver.getConfig();
        Field f = findConfigField(key);
        if (current == null || f == null) {
            source.sendFailure(Component.literal("unknown key: " + key + ". valid: " + String.join(", ", configKeys())));
            return 0;
        }

        String value = rawValue.trim();
        Object parsed;
        try {
            parsed = com.dripps.voxyserver.config.ConfigValueParser.parse(f.getType(),value);
        } catch (RuntimeException e) {
            source.sendFailure(Component.literal("invalid value for " + key + ": " + e.getMessage()));
            return 0;
        }

        VoxyServerConfig candidate = current.copy();
        try {
            f.set(candidate, parsed);
        } catch (IllegalAccessException e) {
            source.sendFailure(Component.literal("failed to set " + key));
            return 0;
        }

        // Keep maxClientVoxyRadius and lodStreamRadius in sync
        if (key.equals("maxClientVoxyRadius")) {
            candidate.lodStreamRadius = candidate.maxClientVoxyRadius;
        } else if (key.equals("lodStreamRadius")) {
            candidate.maxClientVoxyRadius = candidate.lodStreamRadius;
        }

        List<String> errors = candidate.validate();
        if (!errors.isEmpty()) {
            source.sendFailure(Component.literal("invalid: " + String.join("; ", errors)));
            return 0;
        }

        List<String> notes = mod.applyConfigFromCommand(candidate);
        MutableComponent ok = Component.literal("[VoxyServer] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("set ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(key).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" = ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(value).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" (saved & applied live)").withStyle(ChatFormatting.DARK_GREEN));
        source.sendSuccess(() -> ok, true);
        for (String note : notes) {
            source.sendSuccess(() -> Component.literal("note: " + note).withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    private static int showHelp(CommandSourceStack source) {
        VoxyServerConfig cfg = Voxyserver.getConfig();
        MutableComponent out = Component.literal("=== VoxyServer Admin Commands ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        out.append(Component.literal("\n/voxyserver config reset [key] - Restore all settings or one key to defaults").withStyle(ChatFormatting.YELLOW));
        out.append(Component.literal("\n/voxyserver enabled [true|false] - Start or fully stop the server runtime").withStyle(ChatFormatting.YELLOW));
        out.append(Component.literal("\n/voxyserver radius [chunks] ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("- Get or set client LOD view radius (1-4096)").withStyle(ChatFormatting.GRAY));
        out.append(Component.literal("\n/voxyserver bandwidth [bytes/s] ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("- Get or set outbound bandwidth limit (0=unlimited)").withStyle(ChatFormatting.GRAY));
        out.append(Component.literal("\n/voxyserver hashsync [true|false] ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("- Get or set incremental hash synchronization").withStyle(ChatFormatting.GRAY));
        out.append(Component.literal("\n/voxyserver pacing [true|false] ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("- Get or set distance-based packet pacing").withStyle(ChatFormatting.GRAY));
        out.append(Component.literal("\n/voxyserver regionhash [true|false] ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("- Get or set 512x512 region hash synchronization").withStyle(ChatFormatting.GRAY));
        out.append(Component.literal("\n/voxyserver threads [1-10000] ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("- Get or set LOD meshing worker thread count").withStyle(ChatFormatting.GRAY));
        out.append(Component.literal("\n/voxyserver reload ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("- Reload voxyserver.json from disk & apply live").withStyle(ChatFormatting.GRAY));
        out.append(Component.literal("\n/voxyserver config <list|get|set|reload> ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("- Manage all advanced configuration values").withStyle(ChatFormatting.GRAY));
        out.append(Component.literal("\n/voxyserver stats [continuous|stop] ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("- Monitor streaming engine performance").withStyle(ChatFormatting.GRAY));
        out.append(Component.literal("\n/voxyserver import existing ... ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("- Generate LODs for pre-existing worlds").withStyle(ChatFormatting.GRAY));

        if (cfg != null) {
            out.append(Component.literal("\n\nActive Status: ").withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.BOLD))
                    .append(kv("Radius", cfg.maxClientVoxyRadius + "c", ChatFormatting.WHITE)).append(sep())
                    .append(kv("Bandwidth", cfg.maxBandwidthBytesPerSec == 0 ? "Unlimited" : (cfg.maxBandwidthBytesPerSec / 1024) + " KB/s", ChatFormatting.WHITE)).append(sep())
                    .append(kv("Threads", cfg.workerThreads, ChatFormatting.WHITE)).append(sep())
                    .append(kvc("HashSync", onOffComp(cfg.hashSyncEnabled))).append(sep())
                    .append(kvc("Pacing", onOffComp(cfg.enableDistancePacing)));
        }

        source.sendSuccess(() -> out, false);
        return 1;
    }

    private static int getRadius(CommandSourceStack source) {
        VoxyServerConfig cfg = Voxyserver.getConfig();
        if (cfg == null) {
            source.sendFailure(Component.literal("config not loaded"));
            return 0;
        }
        MutableComponent out = Component.literal("[VoxyServer] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("Max client LOD radius: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(cfg.maxClientVoxyRadius + " chunks (" + (cfg.maxClientVoxyRadius * 16) + " blocks)").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" [lodStreamRadius=" + cfg.lodStreamRadius + "]").withStyle(ChatFormatting.DARK_GRAY));
        source.sendSuccess(() -> out, false);
        return 1;
    }

    private static int setRadius(CommandSourceStack source, Voxyserver mod, int radius) {
        VoxyServerConfig current = Voxyserver.getConfig();
        if (current == null) {
            source.sendFailure(Component.literal("config not loaded"));
            return 0;
        }
        VoxyServerConfig candidate = current.copy();
        candidate.maxClientVoxyRadius = radius;
        candidate.lodStreamRadius = radius;
        List<String> errors = candidate.validate();
        if (!errors.isEmpty()) {
            source.sendFailure(Component.literal("invalid: " + String.join("; ", errors)));
            return 0;
        }
        List<String> notes = mod.applyConfigFromCommand(candidate);
        MutableComponent ok = Component.literal("[VoxyServer] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("Max client LOD radius set to ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(radius + " chunks (" + (radius * 16) + " blocks)").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" (broadcast to clients & saved)").withStyle(ChatFormatting.DARK_GREEN));
        source.sendSuccess(() -> ok, true);
        for (String note : notes) {
            source.sendSuccess(() -> Component.literal("note: " + note).withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    private static int getBandwidth(CommandSourceStack source) {
        VoxyServerConfig cfg = Voxyserver.getConfig();
        if (cfg == null) {
            source.sendFailure(Component.literal("config not loaded"));
            return 0;
        }
        MutableComponent out = Component.literal("[VoxyServer] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("Per-player bandwidth limit: ").withStyle(ChatFormatting.GRAY))
                .append(formatBandwidth(cfg.maxBandwidthBytesPerSec));
        source.sendSuccess(() -> out, false);
        return 1;
    }

    private static int setBandwidth(CommandSourceStack source, Voxyserver mod, int bytesPerSec) {
        VoxyServerConfig current = Voxyserver.getConfig();
        if (current == null) {
            source.sendFailure(Component.literal("config not loaded"));
            return 0;
        }
        VoxyServerConfig candidate = current.copy();
        candidate.maxBandwidthBytesPerSec = bytesPerSec;
        List<String> errors = candidate.validate();
        if (!errors.isEmpty()) {
            source.sendFailure(Component.literal("invalid: " + String.join("; ", errors)));
            return 0;
        }
        List<String> notes = mod.applyConfigFromCommand(candidate);
        MutableComponent ok = Component.literal("[VoxyServer] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("Per-player bandwidth limit set to ").withStyle(ChatFormatting.GREEN))
                .append(formatBandwidth(bytesPerSec))
                .append(Component.literal(" (applied live to active player buckets & saved)").withStyle(ChatFormatting.DARK_GREEN));
        source.sendSuccess(() -> ok, true);
        for (String note : notes) {
            source.sendSuccess(() -> Component.literal("note: " + note).withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    private static MutableComponent formatBandwidth(int bytesPerSec) {
        if (bytesPerSec <= 0) {
            return Component.literal("Unlimited (throttling disabled)").withStyle(ChatFormatting.GREEN);
        }
        double kb = bytesPerSec / 1024.0;
        String formatted = String.format("%,d B/s (~%.1f KB/s)", bytesPerSec, kb);
        return Component.literal(formatted).withStyle(ChatFormatting.WHITE);
    }

    private static int getThreads(CommandSourceStack source) {
        VoxyServerConfig cfg = Voxyserver.getConfig();
        if (cfg == null) {
            source.sendFailure(Component.literal("config not loaded"));
            return 0;
        }
        MutableComponent out = Component.literal("[VoxyServer] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("LOD meshing worker threads: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.valueOf(cfg.workerThreads)).withStyle(ChatFormatting.WHITE));
        source.sendSuccess(() -> out, false);
        return 1;
    }

    private static int setThreads(CommandSourceStack source, Voxyserver mod, int count) {
        VoxyServerConfig current = Voxyserver.getConfig();
        if (current == null) {
            source.sendFailure(Component.literal("config not loaded"));
            return 0;
        }
        VoxyServerConfig candidate = current.copy();
        candidate.workerThreads = count;
        List<String> errors = candidate.validate();
        if (!errors.isEmpty()) {
            source.sendFailure(Component.literal("invalid: " + String.join("; ", errors)));
            return 0;
        }
        List<String> notes = mod.applyConfigFromCommand(candidate);
        MutableComponent ok = Component.literal("[VoxyServer] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("Worker threads set to ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(String.valueOf(count)).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" (applied live to engine & saved)").withStyle(ChatFormatting.DARK_GREEN));
        source.sendSuccess(() -> ok, true);
        for (String note : notes) {
            source.sendSuccess(() -> Component.literal("note: " + note).withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    private static int getHashSync(CommandSourceStack source) {
        VoxyServerConfig cfg = Voxyserver.getConfig();
        if (cfg == null) {
            source.sendFailure(Component.literal("config not loaded"));
            return 0;
        }
        MutableComponent out = Component.literal("[VoxyServer] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("Hash synchronization: ").withStyle(ChatFormatting.GRAY))
                .append(onOffComp(cfg.hashSyncEnabled));
        source.sendSuccess(() -> out, false);
        return 1;
    }

    private static int setHashSync(CommandSourceStack source, Voxyserver mod, boolean enabled) {
        VoxyServerConfig current = Voxyserver.getConfig();
        if (current == null) {
            source.sendFailure(Component.literal("config not loaded"));
            return 0;
        }
        VoxyServerConfig candidate = current.copy();
        candidate.hashSyncEnabled = enabled;
        List<String> notes = mod.applyConfigFromCommand(candidate);
        MutableComponent ok = Component.literal("[VoxyServer] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("Hash synchronization set to ").withStyle(ChatFormatting.GREEN))
                .append(onOffComp(enabled))
                .append(Component.literal(" (broadcast to clients & saved)").withStyle(ChatFormatting.DARK_GREEN));
        source.sendSuccess(() -> ok, true);
        for (String note : notes) {
            source.sendSuccess(() -> Component.literal("note: " + note).withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    private static int getPacing(CommandSourceStack source) {
        VoxyServerConfig cfg = Voxyserver.getConfig();
        if (cfg == null) {
            source.sendFailure(Component.literal("config not loaded"));
            return 0;
        }
        MutableComponent out = Component.literal("[VoxyServer] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("Distance pacing: ").withStyle(ChatFormatting.GRAY))
                .append(onOffComp(cfg.enableDistancePacing));
        source.sendSuccess(() -> out, false);
        return 1;
    }

    private static int setPacing(CommandSourceStack source, Voxyserver mod, boolean enabled) {
        VoxyServerConfig current = Voxyserver.getConfig();
        if (current == null) {
            source.sendFailure(Component.literal("config not loaded"));
            return 0;
        }
        VoxyServerConfig candidate = current.copy();
        candidate.enableDistancePacing = enabled;
        List<String> notes = mod.applyConfigFromCommand(candidate);
        MutableComponent ok = Component.literal("[VoxyServer] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("Distance pacing set to ").withStyle(ChatFormatting.GREEN))
                .append(onOffComp(enabled))
                .append(Component.literal(" (applied live & saved)").withStyle(ChatFormatting.DARK_GREEN));
        source.sendSuccess(() -> ok, true);
        for (String note : notes) {
            source.sendSuccess(() -> Component.literal("note: " + note).withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    private static int getRegionHash(CommandSourceStack source) {
        VoxyServerConfig cfg = Voxyserver.getConfig();
        if (cfg == null) {
            source.sendFailure(Component.literal("config not loaded"));
            return 0;
        }
        MutableComponent out = Component.literal("[VoxyServer] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("Region hash synchronization: ").withStyle(ChatFormatting.GRAY))
                .append(onOffComp(cfg.enableRegionHashSync));
        source.sendSuccess(() -> out, false);
        return 1;
    }

    private static int setRegionHash(CommandSourceStack source, Voxyserver mod, boolean enabled) {
        VoxyServerConfig current = Voxyserver.getConfig();
        if (current == null) {
            source.sendFailure(Component.literal("config not loaded"));
            return 0;
        }
        VoxyServerConfig candidate = current.copy();
        candidate.enableRegionHashSync = enabled;
        List<String> notes = mod.applyConfigFromCommand(candidate);
        MutableComponent ok = Component.literal("[VoxyServer] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("Region hash synchronization set to ").withStyle(ChatFormatting.GREEN))
                .append(onOffComp(enabled))
                .append(Component.literal(" (applied live & saved)").withStyle(ChatFormatting.DARK_GREEN));
        source.sendSuccess(() -> ok, true);
        for (String note : notes) {
            source.sendSuccess(() -> Component.literal("note: " + note).withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    private static int configReload(CommandSourceStack source, Voxyserver mod) {
        List<String> notes = mod.reloadConfigFromCommand();
        if (notes == null) {
            source.sendFailure(Component.literal("[VoxyServer] Failed to reload configuration from disk (check server log for syntax or validation errors)"));
            return 0;
        }
        MutableComponent ok = Component.literal("[VoxyServer] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("Configuration reloaded successfully from disk and applied live.").withStyle(ChatFormatting.GREEN));
        source.sendSuccess(() -> ok, true);
        for (String note : notes) {
            source.sendSuccess(() -> Component.literal("note: " + note).withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    private static boolean parseBoolStrict(String s) {
        if (s.equalsIgnoreCase("true")) return true;
        if (s.equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException("not a boolean");
    }

    private static int statsOnce(CommandSourceStack source, Voxyserver mod) {
        if (mod.getStreamingService() == null) {
            source.sendFailure(Component.literal(Voxyserver.getConfig()!=null && !Voxyserver.getConfig().enabled ? "VoxyServer disabled (enabled=false); use /voxyserver enabled true to start" : "engine not started"));
            return 0;
        }
        MutableComponent stats = buildStats(source.getServer(), mod);
        source.sendSuccess(() -> stats, false);
        return 1;
    }

    private static int statsContinuous(CommandSourceStack source, Voxyserver mod) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("continuous stats can only be sent to a player"));
            return 0;
        }
        if (mod.getStreamingService() == null) {
            source.sendFailure(Component.literal(Voxyserver.getConfig()!=null && !Voxyserver.getConfig().enabled ? "VoxyServer disabled (enabled=false); use /voxyserver enabled true to start" : "engine not started"));
            return 0;
        }
        mod.addStatsSubscriber(player.getUUID());
        source.sendSuccess(() -> Component.literal("continuous stats started. /voxyserver stats stop to end")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int statsStop(CommandSourceStack source, Voxyserver mod) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("continuous stats can only be sent to a player"));
            return 0;
        }
        boolean wasOn = mod.removeStatsSubscriber(player.getUUID());
        source.sendSuccess(() -> Component.literal(wasOn ? "continuous stats stopped" : "continuous stats was not running")
                .withStyle(ChatFormatting.YELLOW), false);
        return wasOn ? 1 : 0;
    }

    public static MutableComponent buildStats(MinecraftServer server, Voxyserver mod) {
        LodStreamingService.StreamingStats s = mod.getStreamingService().snapshotStats(server);
        VoxyServerConfig cfg = Voxyserver.getConfig();
        ServerStatsTracker.Snapshot counters = ServerStatsTracker.INSTANCE != null
                ? ServerStatsTracker.INSTANCE.snapshot()
                : new ServerStatsTracker.Snapshot(0, 0, 0);

        MutableComponent out = block("stats");

        out.append(Component.literal("\n"))
                .append(kv("players", s.players(), ChatFormatting.AQUA)).append(sep())
                .append(kv("tracked", s.trackedSections(), ChatFormatting.WHITE)).append(sep())
                .append(kv("hashcache", s.hashCacheSize(), ChatFormatting.WHITE)).append(sep())
                .append(kv("pending", s.pendingDirty(), ChatFormatting.WHITE)).append(sep())
                .append(kv("queued", s.queuedDirty(), ChatFormatting.WHITE)).append(sep())
                .append(kv("chunks", s.loadedChunks(), ChatFormatting.WHITE));

        out.append(Component.literal("\n"))
                .append(kv("sourceSections", mod.getStreamingService().sourceUpdates(), ChatFormatting.WHITE)).append(sep())
                .append(kv("sentNodes", mod.getStreamingService().sentNodeCount(), ChatFormatting.WHITE)).append(sep())
                .append(kv("engine", counters.engineActions(), ChatFormatting.WHITE));

        if (cfg != null) {
            out.append(Component.literal("\n"))
                    .append(kvc("hashsync", onOffComp(cfg.hashSyncEnabled))).append(sep())
                    .append(kvc("dirtytracking", onOffComp(cfg.dirtyTrackingEnabled))).append(sep())
                    .append(kvc("debuglog", onOffComp(cfg.debugTrackingEnabled))).append(sep())
                    .append(kv("radius", cfg.lodStreamRadius, ChatFormatting.WHITE)).append(sep())
                    .append(kv("maxpertick", cfg.maxSectionsPerTickPerPlayer, ChatFormatting.WHITE));
        }

        var background=mod.getBackgroundGenerationStats();
        if(background!=null) {
            out.append(Component.literal("\n"))
                    .append(kv("background",background.state(),ChatFormatting.AQUA)).append(sep())
                    .append(kv("MSPT",String.format(java.util.Locale.ROOT,"%.1f",background.mspt()),ChatFormatting.WHITE)).append(sep())
                    .append(kv("targets",background.completed(),ChatFormatting.WHITE)).append(sep())
                    .append(kv("tickets",background.tickets(),ChatFormatting.WHITE)).append(sep())
                    .append(kv("draining",background.draining(),ChatFormatting.WHITE)).append(sep())
                    .append(kv("failed",background.failed(),ChatFormatting.WHITE));
        }

        for (LodStreamingService.PlayerStat p : s.perPlayer()) {
            out.append(Component.literal("\n"))
                    .append(Component.literal("  " + p.name()).withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal(" sent ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(Integer.toString(p.sentCount())).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" @ ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(p.chunkX() + "," + p.chunkZ()).withStyle(ChatFormatting.WHITE));
        }
        return out;
    }

    private static MutableComponent block(String title) {
        return Component.empty().append(header(title));
    }

    private static MutableComponent header(String title) {
        return Component.literal("[VoxyServer] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(title).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
    }

    private static MutableComponent sep() {
        return Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY);
    }

    private static MutableComponent kv(String label, Object value, ChatFormatting valueColor) {
        return Component.literal(label + " ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.valueOf(value)).withStyle(valueColor));
    }

    private static MutableComponent kvc(String label, Component value) {
        return Component.literal(label + " ").withStyle(ChatFormatting.GRAY).append(value);
    }

    private static MutableComponent onOffComp(boolean b) {
        return Component.literal(b ? "on" : "off").withStyle(b ? ChatFormatting.GREEN : ChatFormatting.RED);
    }
}
