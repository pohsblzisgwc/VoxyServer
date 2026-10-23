package com.dripps.voxyserver;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/** Runs only in the dedicated test launch, before Minecraft can open a world or network port. */
public final class NativeRegressionBootstrap implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        if (!Boolean.getBoolean("voxyserver.nativeRegression")) return;
        try {
            NativeLodIntegrationTest.runAllTests();
            if (Boolean.getBoolean("voxyserver.clientMixinRegression")) {
                for (String name :
                        java.util.List.of(
                                "me.cortex.voxy.client.core.rendering.building.RenderGenerationService",
                                "me.cortex.voxy.client.core.rendering.SectionUpdateRouter",
                                "me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager"))
                    Class.forName(name, false, NativeRegressionBootstrap.class.getClassLoader());
                System.out.println(
                        "PASS client demand mixin targets applied (no display/render session)");
            }
            System.out.println(
                    "PASS Fabric native regression launch (server mixins applied; no client/server"
                        + " E2E)");
            System.exit(0);
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
    }
}
