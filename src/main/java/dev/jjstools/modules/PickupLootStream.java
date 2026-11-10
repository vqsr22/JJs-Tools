package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * A running list of what you have just picked up, in a corner of the screen.
 *
 * Each pickup slides or fades in as its own row with the item's icon, how many and what it is,
 * and slides or fades out again after a few seconds. Picking up more of something already on
 * the list adds to that row instead of starting another, with a little pulse on the number, and
 * keeps it up for longer. When a row goes the ones behind it glide up into its place rather than
 * jumping.
 *
 * Pickups are read from the packet the server sends when you collect an item, the one that
 * plays the pickup animation, so only things that actually went into your inventory show.
 * Almost everything about how it looks can be changed.
 */
public class PickupLootStream extends Module {
    public enum Corner { TopLeft, TopRight, BottomLeft, BottomRight }
    public enum Side { Left, Right }
    public enum CountFormat { PlusN, NTimes, TimesN, Plain }
    public enum CountPlace { BeforeName, AfterName }
    public enum NameColour { Custom, Rarity }
    public enum FontChoice { Default, Uniform, Alt, Illager, Custom }
    public enum Animation { None, Fade, Slide, SlideAndFade }
    public enum Easing { Linear, EaseIn, EaseOut, EaseInOut, Back }
    public enum WidthMode { Fit, Fixed }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgLayout = settings.createGroup("Layout");
    private final SettingGroup sgContent = settings.createGroup("Content");
    private final SettingGroup sgText = settings.createGroup("Text");
    private final SettingGroup sgBackground = settings.createGroup("Background");
    private final SettingGroup sgAnimation = settings.createGroup("Animation");

    // General

    private final Setting<Corner> corner = sgGeneral.add(new EnumSetting.Builder<Corner>()
        .name("corner").description("Which corner of the screen the stream sits in.")
        .defaultValue(Corner.BottomRight).build());

    private final Setting<Integer> offsetX = sgGeneral.add(new IntSetting.Builder()
        .name("offset-x").description("How far in from the side of the screen, in GUI pixels.")
        .defaultValue(4).min(-2000).max(2000).sliderRange(0, 300).build());

    private final Setting<Integer> offsetY = sgGeneral.add(new IntSetting.Builder()
        .name("offset-y").description("How far in from the top or bottom of the screen, in GUI pixels.")
        .defaultValue(4).min(-2000).max(2000).sliderRange(0, 300).build());

    private final Setting<Double> duration = sgGeneral.add(new DoubleSetting.Builder()
        .name("duration").description("How long a row stays up after its last pickup, in seconds.")
        .defaultValue(3.0).min(0.2).max(60.0).sliderRange(0.5, 10.0).build());

    private final Setting<Integer> maxRows = sgGeneral.add(new IntSetting.Builder()
        .name("max-rows").description("Most rows on screen at once. The oldest leaves early to make room.")
        .defaultValue(6).min(1).max(30).sliderRange(1, 15).build());

    private final Setting<Boolean> merge = sgGeneral.add(new BoolSetting.Builder()
        .name("merge-same-items").description("Add a pickup to the row already showing that item, instead of starting another.")
        .defaultValue(true).build());

    private final Setting<Boolean> newestAtCorner = sgGeneral.add(new BoolSetting.Builder()
        .name("newest-at-corner").description("Put the newest row nearest the corner and push older ones away from it. Off adds new rows at the far end.")
        .defaultValue(true).build());

    private final Setting<List<Item>> ignored = sgGeneral.add(new ItemListSetting.Builder()
        .name("ignored-items").description("Items that never show.")
        .build());

    // Layout

    private final Setting<Double> scale = sgLayout.add(new DoubleSetting.Builder()
        .name("scale").description("Size of the whole stream. One is GUI size.")
        .defaultValue(1.0).min(0.25).max(4.0).sliderRange(0.5, 2.0).build());

    private final Setting<WidthMode> widthMode = sgLayout.add(new EnumSetting.Builder<WidthMode>()
        .name("width").description("Fit makes each row as wide as what is in it. Fixed makes every row the same width.")
        .defaultValue(WidthMode.Fit).build());

    private final Setting<Integer> fixedWidth = sgLayout.add(new IntSetting.Builder()
        .name("fixed-width").description("Width of every row, in GUI pixels.")
        .defaultValue(140).min(20).max(1000).sliderRange(60, 300)
        .visible(() -> widthMode.get() == WidthMode.Fixed).build());

    private final Setting<Integer> paddingX = sgLayout.add(new IntSetting.Builder()
        .name("padding-x").description("Space at the ends of each row, in pixels.")
        .defaultValue(5).min(0).max(50).sliderRange(0, 20).build());

    private final Setting<Integer> paddingY = sgLayout.add(new IntSetting.Builder()
        .name("padding-y").description("Space above and below what is in each row, in pixels.")
        .defaultValue(2).min(0).max(50).sliderRange(0, 20).build());

    private final Setting<Integer> spacing = sgLayout.add(new IntSetting.Builder()
        .name("spacing").description("Gap between rows, in pixels.")
        .defaultValue(2).min(0).max(50).sliderRange(0, 20).build());

    private final Setting<Integer> gap = sgLayout.add(new IntSetting.Builder()
        .name("gap").description("Gap between the icon and the text, and between bits of text, in pixels.")
        .defaultValue(4).min(0).max(50).sliderRange(0, 20).build());

    // Content

    private final Setting<Boolean> showIcon = sgContent.add(new BoolSetting.Builder()
        .name("show-icon").description("Show the item's icon.")
        .defaultValue(true).build());

    private final Setting<Side> iconSide = sgContent.add(new EnumSetting.Builder<Side>()
        .name("icon-side").description("Which end of the row the icon goes.")
        .defaultValue(Side.Left).visible(showIcon::get).build());

    private final Setting<Integer> iconSize = sgContent.add(new IntSetting.Builder()
        .name("icon-size").description("Size of the icon, in pixels. A full item is 16.")
        .defaultValue(16).min(4).max(64).sliderRange(8, 32).visible(showIcon::get).build());

    private final Setting<Boolean> showCount = sgContent.add(new BoolSetting.Builder()
        .name("show-count").description("Show how many were picked up.")
        .defaultValue(true).build());

    private final Setting<CountFormat> countFormat = sgContent.add(new EnumSetting.Builder<CountFormat>()
        .name("count-format").description("How the count is written. Plus N is +64, N Times is 64x, Times N is x64, Plain is 64.")
        .defaultValue(CountFormat.PlusN).visible(showCount::get).build());

    private final Setting<CountPlace> countPlace = sgContent.add(new EnumSetting.Builder<CountPlace>()
        .name("count-place").description("Whether the count goes before or after the name.")
        .defaultValue(CountPlace.BeforeName).visible(showCount::get).build());

    private final Setting<Boolean> showName = sgContent.add(new BoolSetting.Builder()
        .name("show-name").description("Show the item's name.")
        .defaultValue(true).build());

    private final Setting<Boolean> useCustomNames = sgContent.add(new BoolSetting.Builder()
        .name("use-custom-names").description("Show a renamed item by its new name rather than what it is.")
        .defaultValue(true).visible(showName::get).build());

    private final Setting<Boolean> showTotal = sgContent.add(new BoolSetting.Builder()
        .name("show-inventory-total").description("Also show how many of that item you now have in your inventory, in brackets.")
        .defaultValue(false).build());

    // Text

    private final Setting<FontChoice> font = sgText.add(new EnumSetting.Builder<FontChoice>()
        .name("font").description("The font. Default is Minecraft's own, Uniform is the smooth Unicode one, Alt is the enchanting table's, Illager is the illager runes. Custom uses a font from a resource pack, by its id.")
        .defaultValue(FontChoice.Default).build());

    private final Setting<String> customFont = sgText.add(new StringSetting.Builder()
        .name("custom-font").description("A font id from a resource pack, such as mypack:myfont.")
        .defaultValue("minecraft:default").visible(() -> font.get() == FontChoice.Custom).build());

    private final Setting<Double> textScale = sgText.add(new DoubleSetting.Builder()
        .name("text-scale").description("Size of the text on its own, on top of the overall scale.")
        .defaultValue(1.0).min(0.25).max(4.0).sliderRange(0.5, 2.0).build());

    private final Setting<Boolean> shadow = sgText.add(new BoolSetting.Builder()
        .name("shadow").description("Drop shadow behind the text.")
        .defaultValue(true).build());

    private final Setting<NameColour> nameColourMode = sgText.add(new EnumSetting.Builder<NameColour>()
        .name("name-colour-mode").description("Custom uses the colour below. Rarity colours the name like its tooltip: white, yellow, aqua or pink.")
        .defaultValue(NameColour.Rarity).build());

    private final Setting<SettingColor> nameColour = sgText.add(new ColorSetting.Builder()
        .name("name-colour").defaultValue(new SettingColor(255, 255, 255))
        .visible(() -> nameColourMode.get() == NameColour.Custom).build());

    private final Setting<SettingColor> countColour = sgText.add(new ColorSetting.Builder()
        .name("count-colour").defaultValue(new SettingColor(120, 255, 140))
        .visible(showCount::get).build());

    private final Setting<SettingColor> totalColour = sgText.add(new ColorSetting.Builder()
        .name("total-colour").defaultValue(new SettingColor(170, 170, 170))
        .visible(showTotal::get).build());

    // Background

    private final Setting<Boolean> background = sgBackground.add(new BoolSetting.Builder()
        .name("background").description("Draw a panel behind each row.")
        .defaultValue(true).build());

    private final Setting<SettingColor> backgroundColour = sgBackground.add(new ColorSetting.Builder()
        .name("background-colour").defaultValue(new SettingColor(10, 10, 14, 150))
        .visible(background::get).build());

    private final Setting<Integer> cornerRadius = sgBackground.add(new IntSetting.Builder()
        .name("corner-radius").description("How rounded the panel's corners are, in pixels.")
        .defaultValue(3).min(0).max(20).sliderRange(0, 10).visible(background::get).build());

    private final Setting<Boolean> accent = sgBackground.add(new BoolSetting.Builder()
        .name("accent-bar").description("A thin coloured strip down the side of each row nearest the screen edge.")
        .defaultValue(true).build());

    private final Setting<Integer> accentWidth = sgBackground.add(new IntSetting.Builder()
        .name("accent-width").defaultValue(2).min(1).max(10).sliderRange(1, 6).visible(accent::get).build());

    private final Setting<Boolean> accentRarity = sgBackground.add(new BoolSetting.Builder()
        .name("accent-rarity-colour").description("Colour the strip by the item's rarity instead of the colour below.")
        .defaultValue(false).visible(accent::get).build());

    private final Setting<SettingColor> accentColour = sgBackground.add(new ColorSetting.Builder()
        .name("accent-colour").defaultValue(new SettingColor(120, 60, 200, 255))
        .visible(() -> accent.get() && !accentRarity.get()).build());

    // Animation

    private final Setting<Animation> animation = sgAnimation.add(new EnumSetting.Builder<Animation>()
        .name("animation").description("How rows come and go.")
        .defaultValue(Animation.SlideAndFade).build());

    private final Setting<Integer> animationTime = sgAnimation.add(new IntSetting.Builder()
        .name("animation-time").description("How long a row takes to come in or go out, in milliseconds.")
        .defaultValue(250).min(0).max(3000).sliderRange(0, 800)
        .visible(() -> animation.get() != Animation.None).build());

    private final Setting<Easing> easing = sgAnimation.add(new EnumSetting.Builder<Easing>()
        .name("easing").description("The shape of the motion. Back overshoots a little and settles.")
        .defaultValue(Easing.EaseOut).visible(() -> animation.get() != Animation.None).build());

    private final Setting<Integer> slideDistance = sgAnimation.add(new IntSetting.Builder()
        .name("slide-distance").description("How far a row slides in from the side, in pixels.")
        .defaultValue(40).min(0).max(500).sliderRange(0, 150)
        .visible(() -> animation.get() == Animation.Slide || animation.get() == Animation.SlideAndFade).build());

    private final Setting<Double> reflowSpeed = sgAnimation.add(new DoubleSetting.Builder()
        .name("reflow-speed").description("How quickly rows glide into a gap left by one that went. Higher is quicker.")
        .defaultValue(12.0).min(1.0).max(60.0).sliderRange(2.0, 30.0).build());

    private final Setting<Boolean> pulse = sgAnimation.add(new BoolSetting.Builder()
        .name("pulse-on-merge").description("Pulse the count when more of an item is added to its row.")
        .defaultValue(true).build());

    public PickupLootStream() {
        super(JJsTools.CATEGORY, "pickup-loot-stream", "Shows what you have just picked up as a smooth, customisable list in a corner of the screen.");
    }

    // Rows

    private static final class Row {
        ItemStack stack;
        int count;
        long born;
        long last;
        long bumped = -1;
        long leaving = -1;
        double slot = -1;
    }

    private final List<Row> rows = new ArrayList<>();
    private long lastFrame;

    @Override
    public void onDeactivate() {
        rows.clear();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        rows.clear();
    }

    /** Called from ClientPlayNetworkHandlerPickupMixin when you pick something up. */
    public static void onPickup(ItemStack stack, int amount) {
        Modules modules = Modules.get();
        if (modules == null) return;

        PickupLootStream module = modules.get(PickupLootStream.class);
        if (module != null && module.isActive()) module.add(stack, amount);
    }

    private void add(ItemStack stack, int amount) {
        if (stack == null || stack.isEmpty() || amount <= 0) return;
        if (ignored.get().contains(stack.getItem())) return;

        long now = System.currentTimeMillis();

        if (merge.get()) {
            for (Row row : rows) {
                if (row.leaving >= 0 || !ItemStack.areItemsAndComponentsEqual(row.stack, stack)) continue;
                row.count += amount;
                row.last = now;
                row.bumped = now;
                return;
            }
        }

        Row row = new Row();
        row.stack = stack.copyWithCount(1);
        row.count = amount;
        row.born = now;
        row.last = now;
        rows.add(row);

        // Too many: the oldest still showing starts leaving now.
        int showing = 0;
        for (Row r : rows) if (r.leaving < 0) showing++;
        for (Row r : rows) {
            if (showing <= maxRows.get()) break;
            if (r.leaving < 0) {
                r.leaving = now;
                showing--;
            }
        }
    }

    // Drawing

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.player == null || mc.options.hudHidden) return;

        long now = System.currentTimeMillis();
        double dt = lastFrame == 0 ? 0 : Math.min(0.1, (now - lastFrame) / 1000.0);
        lastFrame = now;

        long animMs = animation.get() == Animation.None ? 0 : animationTime.get();
        long stayMs = (long) (duration.get() * 1000.0);

        // Start rows leaving once their time is up, and drop those that have finished.
        rows.removeIf(row -> {
            if (row.leaving < 0 && now - row.last > stayMs) row.leaving = now;
            return row.leaving >= 0 && now - row.leaving >= animMs;
        });
        if (rows.isEmpty()) return;

        DrawContext context = event.drawContext;
        TextRenderer tr = mc.textRenderer;
        float s = scale.get().floatValue();

        boolean right = corner.get() == Corner.TopRight || corner.get() == Corner.BottomRight;
        boolean bottom = corner.get() == Corner.BottomLeft || corner.get() == Corner.BottomRight;

        // Working in the stream's own scaled space from here on. Read off the draw context:
        // Meteor's event hands over the screen's width twice, as both width and height, which
        // put anything in a bottom corner below the bottom of the screen.
        double screenW = context.getScaledWindowWidth() / s;
        double screenH = context.getScaledWindowHeight() / s;

        int count = rows.size();
        for (int i = 0; i < count; i++) {
            Row row = rows.get(i);

            // Which slot this row belongs in: 0 is nearest the corner.
            int target = newestAtCorner.get() ? count - 1 - i : i;
            if (row.slot < 0) row.slot = target;
            else row.slot += (target - row.slot) * Math.min(1.0, dt * reflowSpeed.get());

            double shown = visibility(row, now, animMs);
            if (shown <= 0.0) continue;

            Layout layout = layout(row, tr);
            double rowH = layout.height;
            double pitch = rowH + spacing.get();

            double x = right ? screenW - offsetX.get() / s - layout.width : offsetX.get() / s;
            double y = bottom ? screenH - offsetY.get() / s - rowH - row.slot * pitch : offsetY.get() / s + row.slot * pitch;

            boolean slides = animation.get() == Animation.Slide || animation.get() == Animation.SlideAndFade;
            boolean fades = animation.get() == Animation.Fade || animation.get() == Animation.SlideAndFade;
            if (slides) x += (right ? 1 : -1) * (1.0 - shown) * slideDistance.get();
            float alpha = fades ? (float) Math.max(0.0, Math.min(1.0, shown)) : 1.0F;

            dev.jjstools.compat.Gui.push(context);
            dev.jjstools.compat.Gui.scale(context, s, s);
            dev.jjstools.compat.Gui.translate(context, (float) x, (float) y);
            drawRow(context, tr, row, layout, alpha, right, now);
            dev.jjstools.compat.Gui.pop(context);
        }
    }

    /** How far in a row is, from 0 just arriving or gone to 1 fully shown. Can overshoot with Back. */
    private double visibility(Row row, long now, long animMs) {
        if (animMs <= 0) return row.leaving >= 0 ? 0.0 : 1.0;

        double in = ease(Math.min(1.0, (now - row.born) / (double) animMs));
        if (row.leaving < 0) return in;

        double out = Math.min(1.0, (now - row.leaving) / (double) animMs);
        return in * (1.0 - ease(out));
    }

    private double ease(double t) {
        return switch (easing.get()) {
            case Linear -> t;
            case EaseIn -> t * t * t;
            case EaseOut -> 1.0 - Math.pow(1.0 - t, 3.0);
            case EaseInOut -> t < 0.5 ? 4.0 * t * t * t : 1.0 - Math.pow(-2.0 * t + 2.0, 3.0) / 2.0;
            case Back -> {
                double c1 = 1.70158, c3 = c1 + 1.0;
                yield 1.0 + c3 * Math.pow(t - 1.0, 3.0) + c1 * Math.pow(t - 1.0, 2.0);
            }
        };
    }

    /** Where everything in a row goes, worked out before drawing so the row's width is known. */
    private record Layout(double width, double height, Text count, Text name, Text total, double textH) {}

    private Layout layout(Row row, TextRenderer tr) {
        double ts = textScale.get();

        Text countText = showCount.get() ? styled(formatCount(row.count)) : null;
        Text nameText = showName.get() ? styled(nameOf(row.stack)) : null;
        Text totalText = showTotal.get() ? styled("(" + inventoryTotal(row.stack) + ")") : null;

        double textW = 0;
        int parts = 0;
        for (Text t : new Text[] { countText, nameText, totalText }) {
            if (t == null) continue;
            if (parts++ > 0) textW += gap.get();
            textW += tr.getWidth(t) * ts;
        }

        double textH = tr.fontHeight * ts;
        double icon = showIcon.get() ? iconSize.get() : 0;
        double contentH = Math.max(icon, textH);
        double height = contentH + paddingY.get() * 2;

        double width = paddingX.get() * 2 + textW;
        if (icon > 0) width += icon + (parts > 0 ? gap.get() : 0);
        if (accent.get()) width += accentWidth.get();
        if (widthMode.get() == WidthMode.Fixed) width = Math.max(width, fixedWidth.get());

        return new Layout(width, height, countText, nameText, totalText, textH);
    }

    private void drawRow(DrawContext context, TextRenderer tr, Row row, Layout layout, float alpha, boolean right, long now) {
        int w = (int) Math.ceil(layout.width);
        int h = (int) Math.ceil(layout.height);
        final int a = Math.round(alpha * 255.0F);

        if (background.get()) {
            SettingColor bg = backgroundColour.get();
            roundedRect(context, 0, 0, w, h, cornerRadius.get(), argb(Math.round(bg.a * alpha), bg.r, bg.g, bg.b));
        }

        // The strip goes on the side nearest the screen edge.
        int accentW = accent.get() ? accentWidth.get() : 0;
        if (accentW > 0) {
            int colour = accentRarity.get() ? (a << 24) | rarityRgb(row.stack) : argb(a, accentColour.get());
            int ax = right ? w - accentW : 0;
            context.fill(ax, 0, ax + accentW, h, colour);
        }

        double left = paddingX.get() + (right ? 0 : accentW);
        double end = w - paddingX.get() - (right ? accentW : 0);
        double icon = showIcon.get() ? iconSize.get() : 0;

        boolean iconLeft = iconSide.get() == Side.Left;
        double iconX = iconLeft ? left : end - icon;
        double textX = iconLeft ? left + (icon > 0 ? icon + gap.get() : 0) : left;

        // Items cannot be drawn see-through, so in a fade the icon comes in once the row is half there.
        if (icon > 0 && alpha >= 0.5F) {
            float k = (float) (icon / 16.0);
            dev.jjstools.compat.Gui.push(context);
            dev.jjstools.compat.Gui.translate(context, (float) iconX, (float) ((h - icon) / 2.0));
            dev.jjstools.compat.Gui.scale(context, k, k);
            context.drawItem(row.stack, 0, 0);
            dev.jjstools.compat.Gui.pop(context);
        }

        // The text renderer treats a nearly invisible colour as solid, so very faint text is skipped.
        if (a < 8) return;

        double ts = textScale.get();
        double textY = (h - layout.textH) / 2.0;

        List<Text> order = new ArrayList<>();
        List<Integer> colours = new ArrayList<>();
        List<Double> sizes = new ArrayList<>();

        double pulseScale = 1.0;
        if (pulse.get() && row.bumped >= 0) {
            double p = 1.0 - Math.min(1.0, (now - row.bumped) / 250.0);
            pulseScale = 1.0 + 0.3 * Math.sin(p * Math.PI);
        }

        int nameRgb = nameColourMode.get() == NameColour.Rarity ? rarityRgb(row.stack) : rgb(nameColour.get());
        Runnable addCount = () -> {
            if (layout.count == null) return;
            order.add(layout.count);
            colours.add(argb(a, countColour.get()));
            sizes.add(1.0);
        };

        if (countPlace.get() == CountPlace.BeforeName) addCount.run();
        if (layout.name != null) {
            order.add(layout.name);
            colours.add((a << 24) | nameRgb);
            sizes.add(0.0);
        }
        if (countPlace.get() == CountPlace.AfterName) addCount.run();
        if (layout.total != null) {
            order.add(layout.total);
            colours.add(argb(a, totalColour.get()));
            sizes.add(0.0);
        }

        double cursor = textX;
        for (int i = 0; i < order.size(); i++) {
            Text text = order.get(i);
            double partW = tr.getWidth(text) * ts;
            double k = ts * (sizes.get(i) > 0 ? pulseScale : 1.0);

            // A pulsing count grows about its own middle, so the rest of the row stays put.
            double grow = (k - ts) * tr.getWidth(text);
            dev.jjstools.compat.Gui.push(context);
            dev.jjstools.compat.Gui.translate(context, (float) (cursor - grow / 2.0), (float) (textY - (k - ts) * tr.fontHeight / 2.0));
            dev.jjstools.compat.Gui.scale(context, (float) k, (float) k);
            context.drawText(tr, text, 0, 0, colours.get(i), shadow.get());
            dev.jjstools.compat.Gui.pop(context);

            cursor += partW + gap.get();
        }
    }

    // Helpers

    private Text styled(String text) {
        MutableText t = Text.literal(text);
        Identifier source = fontSource();
        return source == null ? t : t.setStyle(dev.jjstools.compat.Compat.withFont(Style.EMPTY, source));
    }

    private Identifier fontSource() {
        String id = switch (font.get()) {
            case Default -> null;
            case Uniform -> "minecraft:uniform";
            case Alt -> "minecraft:alt";
            case Illager -> "minecraft:illageralt";
            case Custom -> customFont.get().trim();
        };
        if (id == null || id.isEmpty()) return null;

        Identifier parsed = Identifier.tryParse(id);
        return parsed;
    }

    private String formatCount(int n) {
        return switch (countFormat.get()) {
            case PlusN -> "+" + n;
            case NTimes -> n + "x";
            case TimesN -> "x" + n;
            case Plain -> String.valueOf(n);
        };
    }

    private String nameOf(ItemStack stack) {
        return (useCustomNames.get() ? stack.getName() : stack.getItem().getName()).getString();
    }

    private int inventoryTotal(ItemStack stack) {
        int total = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.isOf(stack.getItem())) total += s.getCount();
        }
        return total;
    }

    private static int rarityRgb(ItemStack stack) {
        Formatting f = stack.getRarity().getFormatting();
        Integer value = f == null ? null : f.getColorValue();
        return value == null ? 0xFFFFFF : value & 0xFFFFFF;
    }

    private static int rgb(SettingColor c) {
        return (c.r << 16) | (c.g << 8) | c.b;
    }

    private static int argb(int a, SettingColor c) {
        return argb(Math.round(a * (c.a / 255.0F)), c.r, c.g, c.b);
    }

    private static int argb(int a, int r, int g, int b) {
        return (Math.max(0, Math.min(255, a)) << 24) | (r << 16) | (g << 8) | b;
    }

    /** A filled rectangle with its corners rounded off, a row of pixels at a time. */
    private static void roundedRect(DrawContext context, int x, int y, int w, int h, int radius, int colour) {
        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        if (r == 0) {
            context.fill(x, y, x + w, y + h, colour);
            return;
        }

        context.fill(x, y + r, x + w, y + h - r, colour);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = (int) Math.round(r - Math.sqrt(r * r - dy * dy));
            context.fill(x + inset, y + i, x + w - inset, y + i + 1, colour);
            context.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, colour);
        }
    }
}
