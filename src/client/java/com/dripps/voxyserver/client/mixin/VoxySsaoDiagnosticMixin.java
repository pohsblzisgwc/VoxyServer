package com.dripps.voxyserver.client.mixin;

import me.cortex.voxy.client.core.NormalRenderPipeline;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.rendering.Viewport;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL43C.glCopyImageSubData;

/** Optional diagnostic bypass for isolating Voxy SSAO from dark LOD boundary artifacts. */
@Mixin(NormalRenderPipeline.class)
public abstract class VoxySsaoDiagnosticMixin {
    @Unique private static final AtomicBoolean LOGGED = new AtomicBoolean();

    @Redirect(
            method = "postOpaquePreTranslucent",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lme/cortex/voxy/client/core/SSAO;computeSSAO(Lme/cortex/voxy/client/core/rendering/Viewport;Lme/cortex/voxy/client/core/gl/GlTexture;Lme/cortex/voxy/client/core/gl/GlTexture;Lme/cortex/voxy/client/core/gl/GlTexture;I)V"))
    private void voxyserver$diagnosticSsaoBypass(
            SSAO ssao,
            Viewport<?> viewport,
            GlTexture colourOut,
            GlTexture colourIn,
            GlTexture depth,
            int sourceDepthTexture) {
        if (!Boolean.getBoolean("voxyserver.diagnostic.disableVoxySsao")) {
            ssao.computeSSAO(viewport, colourOut, colourIn, depth, sourceDepthTexture);
            return;
        }
        if (LOGGED.compareAndSet(false, true))
            com.dripps.voxyserver.Voxyserver.LOGGER.warn(
                    "diagnostic: Voxy SSAO bypass is active");
        glCopyImageSubData(
                colourIn.id,
                GL_TEXTURE_2D,
                0,
                0,
                0,
                0,
                colourOut.id,
                GL_TEXTURE_2D,
                0,
                0,
                0,
                0,
                viewport.width,
                viewport.height,
                1);
    }
}
