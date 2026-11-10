package dev.jjstools.util;

import dev.jjstools.modules.Incognito;
import dev.jjstools.modules.TabOptimizer;
import dev.jjstools.tab.LiteTab;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.client.MinecraftClient;

/**
 * What has to be drawn every frame even with Exordium: the crosshair preview, Incognito's
 * covers and the tab list's text. Called right after Minecraft draws the GUI (GameRendererOverlayMixin).
 *
 * Exordium lets the HUD draw only every few frames and shows a saved copy in between, so
 * anything drawn straight to the screen from the HUD showed on one frame and not the next.
 * Without Exordium none of this runs; both are drawn with the HUD as before.
 */
public final class ScreenOverlay {
    private ScreenOverlay() {}

    /** A screen that is never shown, only there for Exordium to see while the preview is drawn. */
    private static final net.minecraft.client.gui.screen.Screen STAND_IN =
        new net.minecraft.client.gui.screen.Screen(net.minecraft.text.Text.empty()) {};

    public static void draw() {
        if (!TabOptimizer.EXORDIUM) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null || mc.options.hudHidden) return;
        Modules modules = Modules.get();
        if (modules == null) return;
        // The crosshair preview goes through Minecraft's GUI drawing, so it gets a GUI pass of
        // its own: drawn into the GUI's draw list and drawn out at once, as Meteor does for its
        // own screens at this point.
        dev.jjstools.modules.StorageGuiTweaks tweaks = modules.get(dev.jjstools.modules.StorageGuiTweaks.class);
        // Only touched at all while the preview is showing.
        if (tweaks != null && tweaks.isActive() && tweaks.updatePreview()) {
            //#if MC >= 1.21.6
            var access = (dev.jjstools.mixin.GameRendererAccessor) mc.gameRenderer;
            var state = access.jjstools$getGuiState();
            state.clear();
            int mx = (int) mc.mouse.getScaledX(mc.getWindow()), my = (int) mc.mouse.getScaledY(mc.getWindow());
            //#if MC >= 1.21.9
            var context = new net.minecraft.client.gui.DrawContext(mc, state, mx, my);
            //#else
            //$$ var context = new net.minecraft.client.gui.DrawContext(mc, state);
            //#endif
            tweaks.drawPreview(context, context.getScaledWindowWidth());
            com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder()
                .clearDepthTexture(mc.getFramebuffer().getDepthAttachment(), 1.0);
            /*
             * Exordium steps into every GUI draw and, with no screen open, swaps it for its saved
             * copy of the HUD. A second draw in the frame was swapped too, which lost the preview
             * and upset its timing, so the whole HUD flickered. It stands aside while a screen is
             * open, so for this one draw it is told one is.
             *
             * Drawn without moving the GUI on a frame (which Meteor's flush also does): twice a
             * frame would age the GUI's cached item images twice as fast.
             */
            var screen = mc.currentScreen;
            mc.currentScreen = STAND_IN;
            try {
                access.jjstools$getGuiRenderer().render(access.jjstools$getFogRenderer()
                    .getFogBuffer(net.minecraft.client.render.fog.FogRenderer.FogType.NONE));
            } finally {
                mc.currentScreen = screen;
            }
            //#else
            //$$ // Before 1.21.6 the GUI draws straight to the screen, so the preview is drawn and
            //$$ // flushed at once, with Exordium told a screen is open as above.
            //$$ var screen = mc.currentScreen;
            //$$ mc.currentScreen = STAND_IN;
            //$$ try {
            //$$     var context = new net.minecraft.client.gui.DrawContext(mc, mc.getBufferBuilders().getEntityVertexConsumers());
            //$$     tweaks.drawPreview(context, context.getScaledWindowWidth());
            //$$     context.draw();
            //$$ } finally {
            //$$     mc.currentScreen = screen;
            //$$ }
            //#endif
        }

        Incognito incognito = modules.get(Incognito.class);
        boolean covers = incognito != null && incognito.isActive() && incognito.anyCovers();
        boolean tab = TabOptimizer.on() && LiteTab.wantsEveryFrame();
        if (!covers && !tab) return;

        Utils.unscaledProjection();
        try {
            if (tab) LiteTab.drawEveryFrame();
            if (covers) incognito.drawCovers();
        } finally {
            Utils.scaledProjection();
        }
    }
}
