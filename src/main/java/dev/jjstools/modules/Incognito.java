package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import net.minecraft.client.gui.DrawContext;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

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
        .name("coord-color").defaultValue(new SettingColor(0, 0, 0, 255)).visible(coordsOn::get).build());

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
        .name("map-color").defaultValue(new SettingColor(0, 0, 0, 255)).visible(mapOn::get).build());

    public Incognito() {
        super(JJsTools.CATEGORY, "incognito", "Covers your coordinates and minimap for screenshots and streaming.");
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (coordsOn.get()) {
            draw(event.drawContext, coordsShape.get(), coordsX.get(), coordsY.get(),
                coordsW.get(), coordsH.get(), coordsColour.get());
        }
        if (mapOn.get()) {
            draw(event.drawContext, mapShape.get(), mapX.get(), mapY.get(),
                mapW.get(), mapH.get(), mapColour.get());
        }
    }

    /**
     * Render2DEvent hands over a vanilla DrawContext rather than Meteor's Renderer2D, so the
     * patches are drawn with fill().
     *
     * The circle is a stack of one pixel strips, since fill only does rectangles. At minimap sizes
     * the stepping is not visible.
     */
    private void draw(DrawContext context, Shape shape, int x, int y, int w, int h, SettingColor colour) {
        int argb = colour.getPacked();

        if (shape == Shape.Rectangle) {
            context.fill(x, y, x + w, y + h, argb);
            return;
        }

        double rx = w / 2.0;
        double ry = h / 2.0;

        for (int row = 0; row < h; row++) {
            double dy = (row + 0.5 - ry) / ry;
            double half = Math.sqrt(Math.max(0, 1 - dy * dy)) * rx;
            if (half <= 0) continue;
            int left = (int) Math.round(x + rx - half);
            int right = (int) Math.round(x + rx + half);
            context.fill(left, y + row, right, y + row + 1, argb);
        }
    }
}
