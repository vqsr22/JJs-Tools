/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 *
 * Adapted for JJ's Tools as JJ's theme, under the GPL-3.0 both projects use: rounded corners,
 * colours taken from the Control Panel's theme, and a header colour of its own.
 */

package dev.jjstools.gui.jjtheme;

import meteordevelopment.meteorclient.gui.DefaultSettingsWidgetFactory;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.renderer.packer.GuiTexture;
import dev.jjstools.gui.jjtheme.widgets.*;
import dev.jjstools.gui.jjtheme.widgets.input.WJJDropdown;
import dev.jjstools.gui.jjtheme.widgets.input.WJJSlider;
import dev.jjstools.gui.jjtheme.widgets.input.WJJTextBox;
import dev.jjstools.gui.jjtheme.widgets.pressable.*;
import meteordevelopment.meteorclient.gui.utils.AlignmentX;
import meteordevelopment.meteorclient.gui.utils.CharFilter;
import meteordevelopment.meteorclient.gui.widgets.*;
import meteordevelopment.meteorclient.gui.widgets.containers.WSection;
import meteordevelopment.meteorclient.gui.widgets.containers.WView;
import meteordevelopment.meteorclient.gui.widgets.containers.WWindow;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WSlider;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.*;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.accounts.Account;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.util.MacWindowUtil;

import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * JJ's theme: Meteor's GUI drawn to look like the Control Panel, with rounded corners and the
 * panel's monkeytype colours, following whichever theme is picked in the panel's settings.
 */
public class JJGuiTheme extends GuiTheme {
    public static final String NAME = "JJ's theme";
    private static JJGuiTheme instance;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgColors = settings.createGroup("Colors");
    private final SettingGroup sgTextColors = settings.createGroup("Text");
    private final SettingGroup sgBackgroundColors = settings.createGroup("Background");
    private final SettingGroup sgOutline = settings.createGroup("Outline");
    private final SettingGroup sgSeparator = settings.createGroup("Separator");
    private final SettingGroup sgScrollbar = settings.createGroup("Scrollbar");
    private final SettingGroup sgSlider = settings.createGroup("Slider");
    private final SettingGroup sgStarscript = settings.createGroup("Starscript");

    // General

    public final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Scale of the GUI.")
        .defaultValue(1)
        .min(0.75)
        .sliderRange(0.75, 4)
        .onSliderRelease()
        .onChanged(aDouble -> {
            if (mc.currentScreen instanceof WidgetScreen) ((WidgetScreen) mc.currentScreen).invalidate();
        })
        .build()
    );

    public final Setting<Double> moduleListScale = sgGeneral.add(new DoubleSetting.Builder()
        .name("module-list-scale")
        .description("The size of the module columns alone. Scale above sizes everything else: the top bar, search, other tabs and module settings.")
        .defaultValue(1)
        .min(0.3)
        .max(4)
        .sliderRange(0.5, 2.5)
        .onSliderRelease()
        .onChanged(v -> {
            if (mc.currentScreen instanceof WidgetScreen) ((WidgetScreen) mc.currentScreen).invalidate();
        })
        .build()
    );

    public final Setting<AlignmentX> moduleAlignment = sgGeneral.add(new EnumSetting.Builder<AlignmentX>()
        .name("module-alignment")
        .description("How module titles are aligned.")
        .defaultValue(AlignmentX.Center)
        .build()
    );

    public final Setting<Boolean> categoryIcons = sgGeneral.add(new BoolSetting.Builder()
        .name("category-icons")
        .description("Adds item icons to module categories.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> hideHUD = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-HUD")
        .description("Hide HUD when in GUI.")
        .defaultValue(false)
        .onChanged(v -> {
            if (mc.currentScreen instanceof WidgetScreen) mc.options.hudHidden = v;
        })
        .build()
    );

    public final Setting<Double> cornerRadius = sgGeneral.add(new DoubleSetting.Builder()
        .name("roundness")
        .description("How round corners are. 4.5 is monkeytype's: half the height of the text.")
        .defaultValue(4.5)
        .range(0, 9)
        .sliderRange(0, 9)
        .build()
    );

    public final Setting<Boolean> panelFont = sgGeneral.add(new BoolSetting.Builder()
        .name("control-panel-font")
        .description("Uses the Control Panel's font for Meteor's GUI too, and changes with it.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> equalColumns = sgGeneral.add(new BoolSetting.Builder()
        .name("equal-columns")
        .description("Makes every module button as wide as the longest module name, so all the columns are the same width.")
        .defaultValue(true)
        .onChanged(v -> {
            if (mc.currentScreen instanceof WidgetScreen) ((WidgetScreen) mc.currentScreen).invalidate();
        })
        .build()
    );

    public final Setting<Boolean> textShadow = sgGeneral.add(new BoolSetting.Builder()
        .name("text-shadow")
        .description("Draws a shadow under the GUI's text. Monkeytype has none.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> lowercase = sgGeneral.add(new BoolSetting.Builder()
        .name("lowercase")
        .description("Writes names, buttons and headings in lowercase like the Control Panel. Descriptions keep their capitals.")
        .defaultValue(true)
        .onChanged(v -> {
            if (mc.currentScreen instanceof WidgetScreen) ((WidgetScreen) mc.currentScreen).invalidate();
        })
        .build()
    );

    public final Setting<Boolean> followControlPanel = sgGeneral.add(new BoolSetting.Builder()
        .name("follow-control-panel")
        .description("Takes its colours from the theme picked in the Control Panel's settings, and changes with it. Turn off to set the colours by hand.")
        .defaultValue(true)
        .build()
    );

    // Colors

    public final Setting<SettingColor> accentColor = color("accent", "Main color of the GUI.", new SettingColor(145, 61, 226));
    public final Setting<SettingColor> checkboxColor = color("checkbox", "Color of checkbox.", new SettingColor(145, 61, 226));
    public final Setting<SettingColor> plusColor = color("plus", "Color of plus button.", new SettingColor(50, 255, 50));
    public final Setting<SettingColor> minusColor = color("minus", "Color of minus button.", new SettingColor(255, 50, 50));
    public final Setting<SettingColor> favoriteColor = color("favorite", "Color of checked favorite button.", new SettingColor(250, 215, 0));

    // Text

    public final Setting<SettingColor> textColor = color(sgTextColors, "text", "Color of text.", new SettingColor(255, 255, 255));
    public final Setting<SettingColor> textSecondaryColor = color(sgTextColors, "text-secondary-text", "Color of secondary text.", new SettingColor(150, 150, 150));
    public final Setting<SettingColor> textHighlightColor = color(sgTextColors, "text-highlight", "Color of text highlighting.", new SettingColor(45, 125, 245, 100));
    public final Setting<SettingColor> titleTextColor = color(sgTextColors, "title-text", "Color of title text.", new SettingColor(255, 255, 255));
    public final Setting<SettingColor> loggedInColor = color(sgTextColors, "logged-in-text", "Color of logged in account name.", new SettingColor(45, 225, 45));
    public final Setting<SettingColor> placeholderColor = color(sgTextColors, "placeholder", "Color of placeholder text.", new SettingColor(255, 255, 255, 20));

    // Background

    public final ThreeStateColorSetting backgroundColor = new ThreeStateColorSetting(
            sgBackgroundColors,
            "background",
            new SettingColor(20, 20, 20, 200),
            new SettingColor(30, 30, 30, 200),
            new SettingColor(40, 40, 40, 200)
    );

    public final Setting<SettingColor> panelColor = color(sgBackgroundColors, "panel", "Color of windows and tooltips.", new SettingColor(50, 52, 55));
    public final Setting<SettingColor> wellColor = color(sgBackgroundColors, "well", "Color of open drop-down lists, a shade off the windows.", new SettingColor(40, 42, 45));
    public final Setting<SettingColor> windowOutlineColor = color(sgBackgroundColors, "window-outline", "Color of the thin line around windows.", new SettingColor(74, 76, 79));

    public final Setting<SettingColor> moduleBackground = color(sgBackgroundColors, "module-background", "Color of module background when active.", new SettingColor(50, 50, 50));

    // Outline

    public final ThreeStateColorSetting outlineColor = new ThreeStateColorSetting(
            sgOutline,
            "outline",
            new SettingColor(0, 0, 0),
            new SettingColor(10, 10, 10),
            new SettingColor(20, 20, 20)
    );

    // Separator

    public final Setting<SettingColor> separatorText = color(sgSeparator, "separator-text", "Color of separator text", new SettingColor(255, 255, 255));
    public final Setting<SettingColor> separatorCenter = color(sgSeparator, "separator-center", "Center color of separators.", new SettingColor(255, 255, 255));
    public final Setting<SettingColor> separatorEdges = color(sgSeparator, "separator-edges", "Color of separator edges.", new SettingColor(225, 225, 225, 150));

    // Scrollbar

    public final ThreeStateColorSetting scrollbarColor = new ThreeStateColorSetting(
            sgScrollbar,
            "Scrollbar",
            new SettingColor(30, 30, 30, 200),
            new SettingColor(40, 40, 40, 200),
            new SettingColor(50, 50, 50, 200)
    );

    // Slider

    public final ThreeStateColorSetting sliderHandle = new ThreeStateColorSetting(
            sgSlider,
            "slider-handle",
            new SettingColor(130, 0, 255),
            new SettingColor(140, 30, 255),
            new SettingColor(150, 60, 255)
    );

    public final Setting<SettingColor> sliderLeft = color(sgSlider, "slider-left", "Color of slider left part.", new SettingColor(100,35,170));
    public final Setting<SettingColor> sliderRight = color(sgSlider, "slider-right", "Color of slider right part.", new SettingColor(50, 50, 50));

    // Starscript

    private final Setting<SettingColor> starscriptText = color(sgStarscript, "starscript-text", "Color of text in Starscript code.", new SettingColor(169, 183, 198));
    private final Setting<SettingColor> starscriptBraces = color(sgStarscript, "starscript-braces", "Color of braces in Starscript code.", new SettingColor(150, 150, 150));
    private final Setting<SettingColor> starscriptParenthesis = color(sgStarscript, "starscript-parenthesis", "Color of parenthesis in Starscript code.", new SettingColor(169, 183, 198));
    private final Setting<SettingColor> starscriptDots = color(sgStarscript, "starscript-dots", "Color of dots in starscript code.", new SettingColor(169, 183, 198));
    private final Setting<SettingColor> starscriptCommas = color(sgStarscript, "starscript-commas", "Color of commas in starscript code.", new SettingColor(169, 183, 198));
    private final Setting<SettingColor> starscriptOperators = color(sgStarscript, "starscript-operators", "Color of operators in Starscript code.", new SettingColor(169, 183, 198));
    private final Setting<SettingColor> starscriptStrings = color(sgStarscript, "starscript-strings", "Color of strings in Starscript code.", new SettingColor(106, 135, 89));
    private final Setting<SettingColor> starscriptNumbers = color(sgStarscript, "starscript-numbers", "Color of numbers in Starscript code.", new SettingColor(104, 141, 187));
    private final Setting<SettingColor> starscriptKeywords = color(sgStarscript, "starscript-keywords", "Color of keywords in Starscript code.", new SettingColor(204, 120, 50));
    private final Setting<SettingColor> starscriptAccessedObjects = color(sgStarscript, "starscript-accessed-objects", "Color of accessed objects (before a dot) in Starscript code.", new SettingColor(152, 118, 170));

    public JJGuiTheme() {
        super(NAME);

        settingsFactory = new DefaultSettingsWidgetFactory(this);
        instance = this;
    }

    /**
     * One of the Control Panel's units at this GUI's size. The panel measures everything against
     * Minecraft's nine-high text, so tying this to the text height keeps the proportions the same
     * whatever font and scale Meteor's GUI uses.
     */
    public double unit() {
        return textHeight(false) / 9.0;
    }

    /** How round corners are: monkeytype's half an em, the same on buttons, boxes and windows. */
    public double radius() {
        return unit() * cornerRadius.get();
    }

    public double windowRadius() {
        return radius();
    }

    /**
     * The space inside buttons and boxes: monkeytype's half an em, tied to the text so it keeps
     * its proportions at any GUI scale and font.
     */
    @Override
    public double pad() {
        return Math.round(textHeight(false) * 0.45);
    }

    // Caches. The module list alone draws a few hundred names every frame, and each was being
    // lowercased and measured afresh every time, which is what the GUI spent its frames on.

    private final java.util.HashMap<String, String> lowered = new java.util.HashMap<>();
    private final java.util.HashMap<String, Double> widths = new java.util.HashMap<>();
    private double widthsScale = -1;
    private Object widthsRenderer;

    /** Text as the Control Panel writes it: lowercase, unless that is turned off. */
    public String shown(String text) {
        if (text == null || !lowercase.get()) return text;
        String v = lowered.get(text);
        if (v == null) {
            if (lowered.size() > 8192) lowered.clear();
            v = text.toLowerCase(java.util.Locale.ROOT);
            lowered.put(text, v);
        }
        return v;
    }

    private double widestName = -1;
    private double widestScale = -1;
    private Object widestRenderer;
    private boolean widestLower;

    /**
     * How wide the longest module name is, for equal columns. Worked out once per scale, font and
     * lowercase setting, as it means measuring every module.
     */
    public double widestModuleName() {
        TextRenderer r = textRenderer();
        if (widestName < 0 || widestScale != scale(1) || widestRenderer != r || widestLower != lowercase.get()) {
            double w = 0;
            for (Module m : meteordevelopment.meteorclient.systems.modules.Modules.get().getAll()) w = Math.max(w, textWidth(shown(m.title)));
            widestName = w;
            widestScale = scale(1);
            widestRenderer = r;
            widestLower = lowercase.get();
        }
        return widestName;
    }

    /** Widths of whole strings are remembered until the scale or the font changes. */
    @Override
    public double textWidth(String text, int length, boolean title) {
        TextRenderer r = textRenderer();
        if (title || length != text.length() || r.isBuilding()) return super.textWidth(text, length, title);
        double sc = scale(1);
        if (sc != widthsScale || r != widthsRenderer) {
            widths.clear();
            widthsScale = sc;
            widthsRenderer = r;
        }
        Double w = widths.get(text);
        if (w == null) {
            if (widths.size() > 8192) widths.clear();
            w = super.textWidth(text, length, false);
            widths.put(text, w);
        }
        return w;
    }

    /** What is written on something: the text colour, or the panel colour while the mouse is on it, as hovering swaps the two. */
    public Color ink(boolean hot) {
        return hot ? panelColor.get() : textColor.get();
    }

    /**
     * Picks JJ's theme every time the game starts. Meteor puts back whichever theme was last used
     * in its own start-up step; this runs after it, as Meteor runs its own steps before any addon's.
     */
    @meteordevelopment.meteorclient.utils.PostInit
    public static void selectOnBoot() {
        if (instance != null) meteordevelopment.meteorclient.gui.GuiThemes.select(NAME);
    }

    // Auto fit

    /**
     * Sets the GUI scale so every module column fits across the screen in one row, with a margin
     * at each side the same as the gap between two columns, and puts the columns back in their
     * row from the left. How tall each one is set to is kept.
     *
     * Columns grow with the scale, so the scale is found by measuring the row and correcting
     * until the row is the width of the screen.
     */
    public void autoFit() {
        List<List<String>> columns = new java.util.ArrayList<>();
        List<String> ids = new java.util.ArrayList<>();
        for (meteordevelopment.meteorclient.systems.modules.Category category : meteordevelopment.meteorclient.systems.modules.Modules.loopCategories()) {
            List<String> names = new java.util.ArrayList<>();
            for (Module m : meteordevelopment.meteorclient.systems.modules.Modules.get().getGroup(category)) {
                if (!meteordevelopment.meteorclient.systems.config.Config.get().hiddenModules.get().contains(m)) names.add(m.title);
            }
            if (!names.isEmpty()) {
                names.add(category.name);
                columns.add(names);
                ids.add(category.name);
            }
        }
        List<String> favourites = new java.util.ArrayList<>();
        for (Module m : meteordevelopment.meteorclient.systems.modules.Modules.get().getAll()) if (m.favorite) favourites.add(m.title);
        if (!favourites.isEmpty()) {
            favourites.add("Favorites");
            columns.add(favourites);
            ids.add("favorites");
        }
        if (columns.isEmpty()) return;

        // Fitted the next time the module columns are on screen (fitTick).
        fitPending = true;
        // Back into one row from the left, as Meteor lays out columns nobody has moved.
        for (String id : ids) {
            meteordevelopment.meteorclient.gui.utils.WindowConfig config = getWindowConfig(id);
            config.x = -1;
            config.y = -1;
        }
    }

    public static void register() {
        JJDraw.registerTextures();
        meteordevelopment.meteorclient.gui.GuiThemes.add(new JJGuiTheme());
    }

    // Following the Control Panel

    private int[] lastPalette;
    private String lastFont;

    /** Called every tick: brings the colours in line with the panel's theme while this theme is in use. */
    private int ticks;
    /** Whether auto fit was pressed and the module columns have not been fitted since. */
    private boolean fitPending;
    /** A module list scale being tried by auto fit, or -1. */
    private double trialScale = -1;

    /**
     * Auto fit, once the module columns are open: the biggest module list scale whose row of
     * columns fits between a margin on the left and the same on the right without wrapping.
     *
     * Each scale tried is laid out for real, straight away and off screen, and the result
     * measured, so the answer is exact and settled before anything is drawn. Text does not grow
     * evenly with the scale (each size is drawn from its own font), so guessing from one
     * measurement and correcting over the next ticks overshot back and forth, and windows from
     * the screen before were sometimes measured too. A halving search finds it in under twenty
     * tries instead, every one from this screen alone.
     */
    private void fitTick() {
        if (!fitPending) return;
        if (!(mc.currentScreen instanceof meteordevelopment.meteorclient.gui.screens.ModulesScreen screen)) return;
        fitPending = false;
        var root = ((dev.jjstools.mixin.WidgetScreenAccessor) screen).jjstools$getRoot();

        double lo = 0.3, hi = 4;
        try {
            if (fits(root, hi)) lo = hi;
            else if (fits(root, lo)) {
                for (int i = 0; i < 18; i++) {
                    double mid = (lo + hi) / 2;
                    if (fits(root, mid)) lo = mid;
                    else hi = mid;
                }
            }
        } finally {
            trialScale = -1;
        }
        moduleListScale.set(Math.floor(lo * 10000) / 10000);
        screen.invalidate();
    }

    /** Lays the screen out with the module columns at a trial scale and says whether the row fits. */
    private boolean fits(meteordevelopment.meteorclient.gui.widgets.containers.WContainer root, double trial) {
        trialScale = trial;
        root.calculateSize();
        root.calculateWidgetPositions();

        List<meteordevelopment.meteorclient.gui.widgets.containers.WWindow> columns = new java.util.ArrayList<>();
        collectColumns(root, columns);
        if (columns.isEmpty()) return true;
        double right = 0, top = Double.MAX_VALUE, lowestTop = -Double.MAX_VALUE;
        for (var w : columns) {
            right = Math.max(right, w.x + w.width);
            // Columns lower in a stack are meant to be lower; only the tops of stacks tell of a wrap.
            if (ColumnStacks.above(w) != null) continue;
            top = Math.min(top, w.y);
            lowestTop = Math.max(lowestTop, w.y);
        }
        double margin = scale(4);
        return lowestTop - top <= 1 && right <= meteordevelopment.meteorclient.utils.Utils.getWindowWidth() - margin + 0.5;
    }

    private static void collectColumns(meteordevelopment.meteorclient.gui.widgets.WWidget widget, List<meteordevelopment.meteorclient.gui.widgets.containers.WWindow> out) {
        if (widget instanceof dev.jjstools.gui.jjtheme.widgets.WJJWindow w && w.isModuleColumn()) {
            out.add(w);
            return;
        }
        if (widget instanceof meteordevelopment.meteorclient.gui.widgets.containers.WContainer c) {
            for (var cell : c.cells) collectColumns(cell.widget(), out);
        }
    }

    public static void tick() {
        JJGuiTheme t = instance;
        if (!(mc.currentScreen instanceof WidgetScreen)) dev.jjstools.gui.jjtheme.widgets.WJJWindow.resetCursor();
        if (t == null || meteordevelopment.meteorclient.gui.GuiThemes.get() != t) return;
        // Every tick for the RGB theme, whose colours keep moving; a few times a second otherwise.
        boolean rgb = dev.jjstools.panel.Ui.currentTheme() == dev.jjstools.panel.Ui.Theme.RGB;
        if (t.followControlPanel.get() && (rgb || t.ticks++ % 5 == 0)) t.syncColours();
        t.fitTick();
        if (t.panelFont.get()) {
            String font = dev.jjstools.panel.Prefs.get().font;
            if (font == null) font = dev.jjstools.panel.PanelFonts.MINECRAFT;
            if (!font.equals(t.lastFont)) {
                t.lastFont = font;
                dev.jjstools.gui.MeteorFonts.applyToMeteor(font);
            }
        }
    }

    /**
     * The panel's colours, given out the way monkeytype uses its own: the background for windows,
     * the darker sub colour for headers, buttons and the active module, the main colour as the
     * accent, and the text and dim text colours for text.
     */
    private void syncColours() {
        int[] p = dev.jjstools.panel.Ui.palette(dev.jjstools.panel.Ui.currentTheme());
        if (java.util.Arrays.equals(p, lastPalette)) return;
        lastPalette = p;
        int panel = p[0], well = p[1], button = p[2], accent = p[3], text = p[4], dim = p[5];

        // Text: setting names and values in the text colour, window titles and group headings in the dim one, as monkeytype's settings pages.
        put(textColor.get(), text, 255);
        put(textSecondaryColor.get(), dim, 255);
        put(titleTextColor.get(), dim, 255);
        put(textHighlightColor.get(), accent, 90);
        put(loggedInColor.get(), accent, 255);
        put(placeholderColor.get(), dim, 255);

        put(accentColor.get(), accent, 255);
        put(checkboxColor.get(), accent, 255);
        put(plusColor.get(), text, 255);
        put(minusColor.get(), text, 255);
        put(favoriteColor.get(), accent, 255);

        // Windows: the panel colour with a thin line a step towards the dim text, as the panel's.
        put(panelColor.get(), panel, 255);
        put(windowOutlineColor.get(), mix(panel, dim, 0.28F), 255);
        put(wellColor.get(), well, 255);

        // Buttons and boxes sit in the button shade; under the mouse they turn to the text colour
        // with the panel colour written on them, monkeytype's swap.
        put(backgroundColor.get(false, false, true), button, 255);
        put(backgroundColor.get(false, true, true), text, 255);
        // Held down, monkeytype's buttons go to the dim colour, so a click can be seen.
        put(backgroundColor.get(true, false, true), dim, 255);
        // On, as a module that is turned on: the main colour.
        put(moduleBackground.get(), accent, 255);

        // No outlines on buttons, as the panel has none.
        put(outlineColor.get(false, false, true), button, 0);
        put(outlineColor.get(false, true, true), text, 0);
        put(outlineColor.get(true, false, true), dim, 0);

        // Group headings in the dim sub colour, as monkeytype's settings sections ("hide elements").
        put(separatorText.get(), dim, 255);
        put(separatorCenter.get(), dim, 46);
        put(separatorEdges.get(), dim, 46);

        // Monkeytype's scroll bar: the dim colour, the text colour under the mouse, the main colour while dragged.
        put(scrollbarColor.get(false, false, true), dim, 255);
        put(scrollbarColor.get(false, true, true), text, 255);
        put(scrollbarColor.get(true, false, true), accent, 255);

        // Monkeytype's slider: a chunky thumb in the main colour on a track in the button shade.
        put(sliderHandle.get(false, false, true), accent, 255);
        put(sliderHandle.get(false, true, true), text, 255);
        put(sliderHandle.get(true, false, true), dim, 255);
        put(sliderLeft.get(), accent, 255);
        put(sliderRight.get(), button, 255);
    }

    private static void put(SettingColor c, int argb, int alpha) {
        c.rainbow = false;
        c.set((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, alpha);
    }

    private static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private Setting<SettingColor> color(SettingGroup group, String name, String description, SettingColor color) {
        return group.add(new ColorSetting.Builder()
                .name(name + "-color")
                .description(description)
                .defaultValue(color)
                .build());
    }
    private Setting<SettingColor> color(String name, String description, SettingColor color) {
        return color(sgColors, name, description, color);
    }

    // Widgets

    @Override
    public WWindow window(WWidget icon, String title) {
        return w(new WJJWindow(icon, title));
    }

    @Override
    public WLabel label(String text, boolean title, double maxWidth) {
        if (maxWidth == 0 && !text.contains("\n")) return w(new WJJLabel(text, title));
        return w(new WJJMultiLabel(text, title, maxWidth));
    }

    @Override
    public WHorizontalSeparator horizontalSeparator(String text) {
        return w(new WJJHorizontalSeparator(text));
    }

    @Override
    public WVerticalSeparator verticalSeparator() {
        return w(new WJJVerticalSeparator());
    }

    @Override
    protected WButton button(String text, GuiTexture texture) {
        return w(new WJJButton(text, texture));
    }

    @Override
    protected WConfirmedButton confirmedButton(String text, String confirmText, GuiTexture texture) {
        return w(new WJJConfirmedButton(text, confirmText, texture));
    }

    @Override
    public WMinus minus() {
        return w(new WJJMinus());
    }

    @Override
    public WConfirmedMinus confirmedMinus() {
        return w(new WJJConfirmedMinus());
    }

    @Override
    public WPlus plus() {
        return w(new WJJPlus());
    }

    @Override
    public WCheckbox checkbox(boolean checked) {
        return w(new WJJCheckbox(checked));
    }

    @Override
    public WSlider slider(double value, double min, double max) {
        return w(new WJJSlider(value, min, max));
    }

    @Override
    public WTextBox textBox(String text, String placeholder, CharFilter filter, Class<? extends WTextBox.Renderer> renderer) {
        return w(new WJJTextBox(text, placeholder, filter, renderer));
    }

    @Override
    public <T> WDropdown<T> dropdown(T[] values, T value) {
        return w(new WJJDropdown<>(values, value));
    }

    @Override
    public WTriangle triangle() {
        return w(new WJJTriangle());
    }

    @Override
    public WTooltip tooltip(String text) {
        return w(new WJJTooltip(text));
    }

    @Override
    public WView view() {
        return w(new WJJView());
    }

    @Override
    public WSection section(String title, boolean expanded, WWidget headerWidget) {
        return w(new WJJSection(title, expanded, headerWidget));
    }

    @Override
    public WAccount account(WidgetScreen screen, Account<?> account) {
        return w(new WJJAccount(screen, account));
    }

    @Override
    public WWidget module(Module module, String title) {
        return w(new WJJModule(module, title));
    }

    @Override
    public WQuad quad(Color color) {
        return w(new WJJQuad(color));
    }

    @Override
    public WTopBar topBar() {
        return w(new WJJTopBar());
    }

    @Override
    public WFavorite favorite(boolean checked) {
        return w(new WJJFavorite(checked));
    }

    // Colors

    @Override
    public Color textColor() {
        return textColor.get();
    }

    @Override
    public Color textSecondaryColor() {
        return textSecondaryColor.get();
    }

    //     Starscript

    @Override
    public Color starscriptTextColor() {
        return starscriptText.get();
    }

    @Override
    public Color starscriptBraceColor() {
        return starscriptBraces.get();
    }

    @Override
    public Color starscriptParenthesisColor() {
        return starscriptParenthesis.get();
    }

    @Override
    public Color starscriptDotColor() {
        return starscriptDots.get();
    }

    @Override
    public Color starscriptCommaColor() {
        return starscriptCommas.get();
    }

    @Override
    public Color starscriptOperatorColor() {
        return starscriptOperators.get();
    }

    @Override
    public Color starscriptStringColor() {
        return starscriptStrings.get();
    }

    @Override
    public Color starscriptNumberColor() {
        return starscriptNumbers.get();
    }

    @Override
    public Color starscriptKeywordColor() {
        return starscriptKeywords.get();
    }

    @Override
    public Color starscriptAccessedObjectColor() {
        return starscriptAccessedObjects.get();
    }

    // Other

    @Override
    public TextRenderer textRenderer() {
        // Meteor's font, baked at the exact size each scale needs so it stays sharp.
        return JJTextRenderer.get();
    }

    /**
     * How deep in a module column the GUI is being laid out or drawn. Inside one, sizes come from
     * the module list scale; everywhere else, from the normal scale.
     */
    private static int moduleScope;

    /** Whether JJ's theme is in use with its text shadow on, for the text renderer. */
    public static boolean shadowText() {
        return instance != null && meteordevelopment.meteorclient.gui.GuiThemes.get() == instance && instance.textShadow.get();
    }

    public static void enterModuleList() {
        moduleScope++;
    }

    public static void exitModuleList() {
        if (moduleScope > 0) moduleScope--;
    }

    private double currentScale() {
        if (moduleScope <= 0) return scale.get();
        return trialScale > 0 ? trialScale : moduleListScale.get();
    }

    @Override
    public double scale(double value) {
        double scaled = value * currentScale();

        if (MacWindowUtil.IS_MAC) {
            scaled /= (double) mc.getWindow().getWidth() / mc.getWindow().getFramebufferWidth();
        }

        return scaled;
    }

    @Override
    public boolean categoryIcons() {
        return categoryIcons.get();
    }

    @Override
    public boolean hideHUD() {
        return hideHUD.get();
    }

    public class ThreeStateColorSetting {
        private final Setting<SettingColor> normal, hovered, pressed;

        public ThreeStateColorSetting(SettingGroup group, String name, SettingColor c1, SettingColor c2, SettingColor c3) {
            normal = color(group, name, "Color of " + name + ".", c1);
            hovered = color(group, "hovered-" + name, "Color of " + name + " when hovered.", c2);
            pressed = color(group, "pressed-" + name, "Color of " + name + " when pressed.", c3);
        }

        public SettingColor get() {
            return normal.get();
        }

        public SettingColor get(boolean pressed, boolean hovered, boolean bypassDisableHoverColor) {
            if (pressed) return this.pressed.get();
            return (hovered && (bypassDisableHoverColor || !disableHoverColor)) ? this.hovered.get() : this.normal.get();
        }

        public SettingColor get(boolean pressed, boolean hovered) {
            return get(pressed, hovered, false);
        }
    }
}
