package dev.jjstools.util;

import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;

/**
 * The switch for every Exordium workaround in JJ's Tools, kept with Meteor's HUD settings (the
 * HUD tab) since Exordium is all about the HUD. Off by default.
 *
 * Exordium only draws the HUD every few frames and shows a saved picture of it in between.
 * Anything drawn straight to the screen rather than into that picture shows on the frames it is
 * drawn and not on the others, so it flickers. That is Meteor's own HUD, any other add-on's HUD
 * text, the crosshair preview, Incognito's covers and the tab list. With this on, those are drawn
 * every frame instead (ScreenOverlay).
 */
public final class ExordiumFixes {
    private ExordiumFixes() {}

    public static final boolean INSTALLED = dev.jjstools.modules.TabOptimizer.EXORDIUM;

    private static Setting<Boolean> enabled;

    /** Whether the HUD was drawn by the game this frame, so it need not be drawn again. */
    private static boolean hudDrawn;
    /** Set while the HUD is being drawn from here, so that draw does not count as the game's. */
    private static boolean drawing;

    public static void init() {
        enabled = Hud.get().settings.createGroup("Exordium").add(new BoolSetting.Builder()
            .name("exordium-fixes")
            .description("Fixes for Exordium, which only draws the HUD every few frames: Meteor's HUD and other add-ons' HUD text, the crosshair storage preview, Incognito's covers and the tab list are drawn every frame instead, so they stop flickering. Only does anything with Exordium installed.")
            .defaultValue(false)
            .build());
        meteordevelopment.meteorclient.MeteorClient.EVENT_BUS.subscribe(ExordiumFixes.class);
    }

    /** Whether the workarounds are in use: Exordium is installed and the switch is on. */
    public static boolean active() {
        return INSTALLED && enabled != null && enabled.get();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private static void onRender2D(Render2DEvent event) {
        if (!drawing) hudDrawn = true;
    }

    /** Whether the game drew the HUD this frame; resets the note for the next frame. */
    static boolean takeHudDrawn() {
        boolean drawn = hudDrawn;
        hudDrawn = false;
        return drawn;
    }

    static void setDrawing(boolean value) {
        drawing = value;
    }
}
