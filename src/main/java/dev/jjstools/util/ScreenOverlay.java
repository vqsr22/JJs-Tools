package dev.jjstools.util;

import dev.jjstools.modules.Incognito;
import dev.jjstools.modules.TabOptimizer;
import dev.jjstools.tab.LiteTab;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.function.Consumer;

/**
 * What has to be drawn every frame even with Exordium: Meteor's HUD (and so every add-on's HUD
 * text) on the frames Exordium skips, the crosshair preview, Incognito's covers and the tab list's
 * text. Called right after Minecraft draws the GUI (GameRendererOverlayMixin).
 *
 * Exordium lets the HUD draw only every few frames and shows a saved copy in between, so
 * anything drawn straight to the screen from the HUD showed on one frame and not the next.
 * Only runs with Exordium installed and its fixes switched on (ExordiumFixes); otherwise all of
 * these are drawn with the HUD as normal.
 */
public final class ScreenOverlay {
    private ScreenOverlay() {}

    /** A screen that is never shown, only there for Exordium to see while these are drawn. */
    private static final net.minecraft.client.gui.screen.Screen STAND_IN =
        new net.minecraft.client.gui.screen.Screen(net.minecraft.text.Text.empty()) {};

    public static void draw() {
        // Read every frame, so a frame the game drew does not count for the next one.
        boolean hudDrawn = ExordiumFixes.takeHudDrawn();
        if (!ExordiumFixes.active()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null || mc.options.hudHidden) return;
        Modules modules = Modules.get();
        if (modules == null) return;

        // The HUD first, so the covers below still go over it. On frames Exordium let the game
        // draw the HUD it is already there; on the others it is drawn from here, the same way
        // Meteor does it at the end of the game's HUD.
        if (!hudDrawn && mc.currentScreen == null) guiPass(mc, ScreenOverlay::drawHud);

        // The crosshair preview goes through Minecraft's GUI drawing, so it gets a GUI pass of
        // its own.
        dev.jjstools.modules.StorageGuiTweaks tweaks = modules.get(dev.jjstools.modules.StorageGuiTweaks.class);
        // Only touched at all while the preview is showing.
        if (tweaks != null && tweaks.isActive() && tweaks.updatePreview()) {
            guiPass(mc, context -> tweaks.drawPreview(context, context.getScaledWindowWidth()));
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

    /** Meteor's HUD, every add-on's included: the same event Meteor sends at the end of the game's HUD. */
    private static void drawHud(DrawContext context) {
        ExordiumFixes.setDrawing(true);
        Utils.unscaledProjection();
        try {
            meteordevelopment.meteorclient.MeteorClient.EVENT_BUS.post(meteordevelopment.meteorclient.events.render.Render2DEvent.get(
                context, context.getScaledWindowWidth(), context.getScaledWindowHeight(), dev.jjstools.compat.Compat.tickDelta()));
        } finally {
            Utils.scaledProjection();
            ExordiumFixes.setDrawing(false);
        }
    }

    /**
     * Draws through Minecraft's GUI drawing once, outside the GUI's own pass.
     *
     * Exordium steps into every GUI draw and, with no screen open, swaps it for its saved copy of
     * the HUD. A second draw in the frame was swapped too, which lost what was drawn and upset its
     * timing, so the whole HUD flickered. It stands aside while a screen is open, so for this draw
     * it is told one is.
     */
    private static void guiPass(MinecraftClient mc, Consumer<DrawContext> draw) {
        var screen = mc.currentScreen;
        mc.currentScreen = STAND_IN;
        try {
            //#if MC >= 1.21.6
            var access = (dev.jjstools.mixin.GameRendererAccessor) mc.gameRenderer;
            var state = access.jjstools$getGuiState();
            state.clear();
            int mx = (int) dev.jjstools.compat.Compat.mouseX(), my = (int) dev.jjstools.compat.Compat.mouseY();
            //#if MC >= 1.21.9
            var context = new DrawContext(mc, state, mx, my);
            //#else
            //$$ var context = new DrawContext(mc, state);
            //#endif
            draw.accept(context);
            com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder()
                .clearDepthTexture(mc.getFramebuffer().getDepthAttachment(), 1.0);
            // Drawn without moving the GUI on a frame (which Meteor's flush also does): twice a
            // frame would age the GUI's cached item images twice as fast.
            access.jjstools$getGuiRenderer().render(access.jjstools$getFogRenderer()
                .getFogBuffer(net.minecraft.client.render.fog.FogRenderer.FogType.NONE));
            //#else
            //$$ // Before 1.21.6 the GUI draws straight to the screen, so it is drawn and flushed at once.
            //$$ var context = new DrawContext(mc, mc.getBufferBuilders().getEntityVertexConsumers());
            //$$ draw.accept(context);
            //$$ context.draw();
            //#endif
        } finally {
            mc.currentScreen = screen;
        }
    }
}
