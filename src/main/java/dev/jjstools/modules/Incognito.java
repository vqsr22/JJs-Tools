package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.renderer.Renderer2D;

/**
 * Covers up anything on screen you do not want in a screenshot or on stream: your coordinates,
 * your minimap, or both.
 *
 * Two independent patches, each with its own shape, position, size and colour. Positions are in
 * pixels from the top left of the screen.
 */
public class Incognito extends Module {
    public enum Shape { Rectangle, Circle }

    private final SettingGroup sgCoords = settings.createGroup("Coord hider");
    private final SettingGroup sgMap = settings.createGroup("Map hider");

    // Coordinate patch

    private final Setting<Boolean> coordsOn = sgCoords.add(new BoolSetting.Builder()
        .name("coord-hider").description("Cover your coordinates.").defaultValue(true).build());

    private final Setting<Shape> coordsShape = sgCoords.add(new EnumSetting.Builder<Shape>()
        .name("coord-shape").defaultValue(Shape.Rectangle).visible(coordsOn::get).build());

    private final Setting<Integer> coordsX = sgCoords.add(new IntSetting.Builder()
        .name("coord-x").description("Pixels from the left edge.")
        .defaultValue(2).min(0).sliderRange(0, 1920).visible(coordsOn::get).build());

    private final Setting<Integer> coordsY = sgCoords.add(new IntSetting.Builder()
        .name("coord-y").description("Pixels from the top edge.")
        .defaultValue(2).min(0).sliderRange(0, 1080).visible(coordsOn::get).build());

    private final Setting<Integer> coordsW = sgCoords.add(new IntSetting.Builder()
        .name("coord-width").defaultValue(160).min(1).sliderRange(10, 1920).visible(coordsOn::get).build());

    private final Setting<Integer> coordsH = sgCoords.add(new IntSetting.Builder()
        .name("coord-height").defaultValue(12).min(1).sliderRange(4, 1080).visible(coordsOn::get).build());

    private final Setting<SettingColor> coordsColour = sgCoords.add(new ColorSetting.Builder()
        .name("coord-colour").defaultValue(new SettingColor(0, 0, 0, 255)).visible(coordsOn::get).build());

    // Map patch

    private final Setting<Boolean> mapOn = sgMap.add(new BoolSetting.Builder()
        .name("map-hider").description("Cover your minimap.").defaultValue(false).build());

    private final Setting<Shape> mapShape = sgMap.add(new EnumSetting.Builder<Shape>()
        .name("map-shape").description("Square minimaps want Rectangle, round ones want Circle.")
        .defaultValue(Shape.Rectangle).visible(mapOn::get).build());

    private final Setting<Integer> mapX = sgMap.add(new IntSetting.Builder()
        .name("map-x").description("Pixels from the left edge.")
        .defaultValue(1700).min(0).sliderRange(0, 1920).visible(mapOn::get).build());

    private final Setting<Integer> mapY = sgMap.add(new IntSetting.Builder()
        .name("map-y").description("Pixels from the top edge.")
        .defaultValue(10).min(0).sliderRange(0, 1080).visible(mapOn::get).build());

    private final Setting<Integer> mapW = sgMap.add(new IntSetting.Builder()
        .name("map-width").defaultValue(100).min(1).sliderRange(10, 1920).visible(mapOn::get).build());

    private final Setting<Integer> mapH = sgMap.add(new IntSetting.Builder()
        .name("map-height").defaultValue(100).min(1).sliderRange(10, 1080).visible(mapOn::get).build());

    private final Setting<SettingColor> mapColour = sgMap.add(new ColorSetting.Builder()
        .name("map-colour").defaultValue(new SettingColor(0, 0, 0, 255)).visible(mapOn::get).build());

    public Incognito() {
        super(JJsTools.CATEGORY, "incognito", "Covers your coordinates and minimap for screenshots and streaming.");
    }

    /**
     * Drawn last, after Meteor's HUD, and straight to the screen with Meteor's own renderer, the
     * same way Meteor's HUD (the coordinates being covered) is drawn.
     *
     * The patches used to go through Minecraft's GUI drawing, which Exordium takes over: it draws
     * the GUI into a buffer of its own every few frames, and these patches never made it into
     * that buffer, so they only showed with a screen open, when Exordium steps aside. Drawing them
     * the way Meteor's HUD is drawn keeps them over the coordinates whatever Exordium does.
     */
    @EventHandler(priority = meteordevelopment.orbit.EventPriority.LOWEST)
    private void onRender2D(Render2DEvent event) {
        // Under Exordium this event only comes on the frames it lets the HUD draw, so the covers
        // flickered; there they are drawn every frame from ScreenOverlay instead.
        if (dev.jjstools.modules.TabOptimizer.EXORDIUM) return;
        drawCovers();
    }

    public boolean anyCovers() {
        return coordsOn.get() || mapOn.get();
    }

    /** Draws the covers in screen pixels, with the projection already set for that. */
    public void drawCovers() {
        if (!coordsOn.get() && !mapOn.get()) return;
        // Positions are in GUI pixels; this renderer works in screen pixels.
        double k = mc.getWindow().getScaleFactor();
        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        if (coordsOn.get()) draw(r, k, coordsShape.get(), coordsX.get(), coordsY.get(), coordsW.get(), coordsH.get(), coordsColour.get());
        if (mapOn.get()) draw(r, k, mapShape.get(), mapX.get(), mapY.get(), mapW.get(), mapH.get(), mapColour.get());
        r.end();
        r.render();
    }

    /** A rectangle, or an ellipse as a stack of one pixel strips. */
    private static void draw(Renderer2D r, double k, Shape shape, int gx, int gy, int gw, int gh, SettingColor colour) {
        double x = gx * k, y = gy * k, w = gw * k, h = gh * k;
        if (shape == Shape.Rectangle) {
            r.quad(x, y, w, h, colour);
            return;
        }
        double rx = w / 2.0, ry = h / 2.0;
        int rows = (int) Math.ceil(h);
        for (int row = 0; row < rows; row++) {
            double dy = (row + 0.5 - ry) / ry;
            double half = Math.sqrt(Math.max(0, 1 - dy * dy)) * rx;
            if (half <= 0) continue;
            r.quad(x + rx - half, y + row, half * 2, 1, colour);
        }
    }
}
