package com.dripps.voxyserver.client;

import com.dripps.voxyserver.network.*;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.util.Locale;

public final class ClientLodSettings {
    private static final ClientLodConfig CONFIG = ClientLodConfig.load();
    private static int serverMaxRadius = -1, serverMaxSections = -1, lastReportedRadius = -1;
    private static volatile boolean protocolOk;
    private static volatile String cacheNamespace = "unbound";
    private static String activeServerKey;
    private static ClientLodConfig.Preferences activePreferences =
            CONFIG.getPreferencesForServer(null);

    public static String hashStoreKey(String worldId) {
        return cacheNamespace + "_" + worldId;
    }

    public static void prepareForCurrentConnection() {
        serverMaxRadius = -1;
        serverMaxSections = -1;
        lastReportedRadius = -1;
        protocolOk = false;
        ClientDemand.reset();
        activeServerKey = resolveCurrentServerKey();
        activePreferences = CONFIG.getPreferencesForServer(activeServerKey);
    }

    public static boolean isProtocolOk() {
        return protocolOk;
    }

    public static void setProtocolOk(boolean value) {
        protocolOk = value;
        if (!value) ClientDemand.reset();
    }

    public static void applyServerSettings(int radius, int sections) {
        serverMaxRadius = radius;
        serverMaxSections = sections;
        sendPreferences();
    }

    public static void applyHashSyncSettings(boolean enabled) {}

    public static void applyStreamSession(LODStreamSessionPayload p) {
        cacheNamespace = p.worldIdentity() + "_semantic" + p.hashVersion();
        ClientDemand.start(p);
    }

    public static void invalidateSession() {
        ClientDemand.reset();
    }

    public static void onClientTick() {
        if (protocolOk && Minecraft.getInstance().getConnection() != null) {
            int radius = resolveVoxyPreferredRadius();
            if (radius > 0 && radius != lastReportedRadius) {
                lastReportedRadius = radius;
                sendPreferences();
            }
        }
        ClientDemand.tick();
    }

    public static int resolveVoxyPreferredRadius() {
        try {
            if (me.cortex.voxy.client.config.VoxyConfig.CONFIG != null) {
                float secDist =
                        me.cortex.voxy.client.config.VoxyConfig.CONFIG.sectionRenderDistance;
                if (Float.isFinite(secDist) && secDist > 0) {
                    int nativeRadius =
                            com.dripps.voxyserver.sync.NativeLodUnits.radiusChunks(secDist);
                    return activePreferences.preferredRadius <= 0
                            ? nativeRadius
                            : Math.min(nativeRadius, activePreferences.preferredRadius);
                }
            }
        } catch (Throwable ignored) {
        }
        return Math.min(4096, activePreferences.preferredRadius);
    }

    public static void reset() {
        prepareForCurrentConnection();
        activeServerKey = null;
    }

    public static boolean hasActiveServerProfile() {
        return activeServerKey != null;
    }

    public static boolean isEnabled() {
        return activePreferences.enabled;
    }

    public static int getServerMaxRadius() {
        return serverMaxRadius;
    }

    public static int getServerMaxSections() {
        return serverMaxSections;
    }

    public static int getConfiguredRadius() {
        return activePreferences.preferredRadius;
    }

    public static int getPreferredRadius() {
        return resolveVoxyPreferredRadius();
    }

    public static int getPreferredMaxSections() {
        return activePreferences.preferredMaxSections;
    }

    public static void setEnabled(boolean value) {
        activePreferences.enabled = value;
        if (!value) ClientLodReceiver.disableRemoteIngest();
    }

    public static void setPreferredRadius(int radius) {
        activePreferences.preferredRadius = Math.max(0, radius);
    }

    public static void setPreferredMaxSections(int maxSections) {
        activePreferences.preferredMaxSections = Math.max(0, maxSections);
    }

    public static void saveAndSendPreferences() {
        saveActiveProfile();
        sendPreferences();
    }

    public static void sendPreferences() {
        if (!protocolOk || Minecraft.getInstance().getConnection() == null) return;
        int radius = resolveVoxyPreferredRadius();
        lastReportedRadius = radius;
        ClientPlayNetworking.send(
                new LODPreferencesPayload(
                        activePreferences.enabled, radius, activePreferences.preferredMaxSections));
    }

    private static void saveActiveProfile() {
        if (activeServerKey != null)
            CONFIG.savePreferencesForServer(activeServerKey, activePreferences);
    }

    private static String resolveCurrentServerKey() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.hasSingleplayerServer() || minecraft.isLocalServer()) return null;
        ServerData currentServer = minecraft.getCurrentServer();
        if (currentServer == null
                || currentServer.isLan()
                || currentServer.ip == null
                || currentServer.ip.isBlank()) return null;
        ServerAddress address = ServerAddress.parseString(currentServer.ip);
        String host = address.getHost();
        if (host == null || host.isBlank()) return null;
        return host.toLowerCase(Locale.ROOT) + ":" + address.getPort();
    }
}
