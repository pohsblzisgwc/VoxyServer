package com.dripps.voxyserver.client;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class VoxyConfigScreen {

    public static Screen create(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("VoxyServer Client Settings"));

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();
        ConfigCategory category = builder.getOrCreateCategory(Component.literal("LOD Streaming"));

        if (!ClientLodSettings.hasActiveServerProfile()) {
            category.addEntry(entryBuilder.startTextDescription(
                    Component.literal("per server overrides can only be edited while connected to a server"))
                    .build());
            return builder.build();
        }

        int maxRadius = ClientLodSettings.getServerMaxRadius();
        int maxSections = ClientLodSettings.getServerMaxSections();

        if (maxRadius <= 0) maxRadius = 256;
        if (maxSections <= 0) maxSections = 50;

        builder.setSavingRunnable(ClientLodSettings::saveAndSendPreferences);

        category.addEntry(entryBuilder.startBooleanToggle(
                        Component.literal("Enable LOD Streaming"),
                        ClientLodSettings.isEnabled())
                .setDefaultValue(true)
                .setTooltip(Component.literal("toggle whether the server sends LOD data to you"))
                .setSaveConsumer(ClientLodSettings::setEnabled)
                .build());

        category.addEntry(entryBuilder.startIntSlider(
                        Component.literal("LOD Stream Radius"),
                        ClientLodSettings.getConfiguredRadius(),
                        0, maxRadius)
                .setDefaultValue(0)
                .setTooltip(Component.literal("maximum remote distance in chunks; also capped by Voxy, 0 = automatic (" + maxRadius + ")"))
                .setSaveConsumer(ClientLodSettings::setPreferredRadius)
                .build());

        category.addEntry(entryBuilder.startIntSlider(
                        Component.literal("Max Sections Per Tick"),
                        ClientLodSettings.getPreferredMaxSections(),
                        0, maxSections)
                .setDefaultValue(0)
                .setTooltip(Component.literal("rate limit for sections sent per tick, 0 = server default (" + maxSections + ")"))
                .setSaveConsumer(ClientLodSettings::setPreferredMaxSections)
                .build());

        return builder.build();
    }
}
