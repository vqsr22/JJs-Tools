package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import dev.jjstools.tab.HeadAtlas;
import dev.jjstools.tab.LiteTab;
import dev.jjstools.tab.SkinCache;
import dev.jjstools.tab.TabCache;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

/**
 * Cuts the per-frame cost of the vanilla tab list while keeping it looking like
 * the vanilla tab list. The expensive parts are the repeated sort in
 * collectPlayerEntries(), per-head skin lookups, and one draw call per head.
 */
public class TabOptimizer extends Module {
    private static TabOptimizer INSTANCE;

    /** True only while PlayerListHud#render is running. */
    public static volatile boolean renderingTab = false;
    /** Heads drawn so far in the current render pass, for head-limit. */
    public static int headsDrawn = 0;
    /** Guards against a delegating head overload counting twice. */
    public static boolean inHead = false;
    /** Last time the tab list render hook ran at all, on or off. */
    public static volatile long lastHookNanos = 0L;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgLite = settings.createGroup("Lite Renderer");
    private final SettingGroup sgColours = settings.createGroup("Colours");
    private final SettingGroup sgHeads = settings.createGroup("Heads");
    private final SettingGroup sgPerformance = settings.createGroup("Performance");

    public final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Size of the whole tab list, width and height together. With auto-fit on this is the biggest it will be drawn; it shrinks further if needed to fit the screen.")
        .defaultValue(1.0)
        .min(0.25)
        .sliderRange(0.25, 2.0)
        .build()
    );

    public final Setting<Boolean> liteRenderer = sgLite.add(new BoolSetting.Builder()
        .name("lite-renderer")
        .description("Draw the tab list with JJ's lite renderer: names, colours, widths and layout are worked out once per cache-time instead of every frame, and one background per column replaces one per player. Does not draw the scoreboard objective column.")
        .defaultValue(true)
        .onChanged(v -> LiteTab.invalidate())
        .build()
    );

    public final Setting<Integer> height = sgLite.add(new IntSetting.Builder()
        .name("height")
        .description("Players per column, which sets how tall the list is. 0: picked automatically with auto-fit, otherwise Better Tab's tab-height or vanilla's 20.")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 100)
        .visible(liteRenderer::get)
        .build()
    );

    public final Setting<Boolean> autoFit = sgLite.add(new BoolSetting.Builder()
        .name("auto-fit")
        .description("Make the whole list fit on screen: picks the height (when height is 0) and shrinks the scale if the list would still be too big.")
        .defaultValue(true)
        .visible(liteRenderer::get)
        .build()
    );

    public final Setting<Boolean> anchor = sgLite.add(new BoolSetting.Builder()
        .name("anchor-to-screen")
        .description("Push the list back inside the screen edges instead of letting it run off.")
        .defaultValue(true)
        .visible(liteRenderer::get)
        .build()
    );

    public final Setting<Boolean> meteorText = sgLite.add(new BoolSetting.Builder()
        .name("meteor-text")
        .description("Draw names with Meteor's text renderer: every name goes into one batch, and it uses Meteor's custom font when Custom Font is on in Meteor's config. Off: vanilla GUI text.")
        .defaultValue(true)
        .visible(liteRenderer::get)
        .onChanged(v -> LiteTab.invalidate())
        .build()
    );

    public final Setting<Boolean> customFontNamesOnly = sgLite.add(new BoolSetting.Builder()
        .name("custom-font-names-only")
        .description("Only player names use Meteor's font. The header (server logo), footer and ping stay in the vanilla font.")
        .defaultValue(true)
        .visible(() -> liteRenderer.get() && meteorText.get())
        .onChanged(v -> LiteTab.invalidate())
        .build()
    );

    public final Setting<Integer> fadeIn = sgLite.add(new IntSetting.Builder()
        .name("fade-in")
        .description("Milliseconds for the tab list to fade in when you open it. 0 = no fade.")
        .defaultValue(150)
        .range(0, 1000)
        .sliderRange(0, 500)
        .visible(liteRenderer::get)
        .build()
    );

    public final Setting<Integer> fadeOut = sgLite.add(new IntSetting.Builder()
        .name("fade-out")
        .description("Milliseconds for the tab list to fade out after you let go of tab. 0 = no fade.")
        .defaultValue(150)
        .range(0, 1000)
        .sliderRange(0, 500)
        .visible(liteRenderer::get)
        .build()
    );

    public final Setting<Boolean> textShadow = sgLite.add(new BoolSetting.Builder()
        .name("text-shadow")
        .description("Draw name shadows. Off halves the text geometry.")
        .defaultValue(false)
        .visible(liteRenderer::get)
        .build()
    );

    public final Setting<Boolean> recolourTitle = sgColours.add(new BoolSetting.Builder()
        .name("recolour-title")
        .description("Recolour the first header line (the server's name, like 2BUILDERS2TOOLS). Lite renderer only.")
        .defaultValue(false)
        .onChanged(v -> LiteTab.invalidate())
        .build()
    );

    public final Setting<SettingColor> titleColour = sgColours.add(new ColorSetting.Builder()
        .name("title-colour")
        .description("Colour for the first header line.")
        .defaultValue(new SettingColor(255, 255, 255))
        .visible(recolourTitle::get)
        .build()
    );

    public final Setting<Boolean> recolourMessage = sgColours.add(new BoolSetting.Builder()
        .name("recolour-server-message")
        .description("Recolour the other header lines (the server message, like \"Server updated to 1.21.4\"). Lite renderer only.")
        .defaultValue(false)
        .onChanged(v -> LiteTab.invalidate())
        .build()
    );

    public final Setting<SettingColor> messageColour = sgColours.add(new ColorSetting.Builder()
        .name("server-message-colour")
        .description("Colour for the server message lines.")
        .defaultValue(new SettingColor(255, 170, 0))
        .visible(recolourMessage::get)
        .build()
    );

    public final Setting<Boolean> recolourFooter = sgColours.add(new BoolSetting.Builder()
        .name("recolour-footer")
        .description("Recolour the footer (tps, players online, ping). Lite renderer only.")
        .defaultValue(true)
        .onChanged(v -> LiteTab.invalidate())
        .build()
    );

    public final Setting<SettingColor> footerColour = sgColours.add(new ColorSetting.Builder()
        .name("footer-colour")
        .description("Colour for the footer lines.")
        .defaultValue(new SettingColor(255, 255, 255))
        .visible(recolourFooter::get)
        .build()
    );

    public final Setting<Boolean> heads = sgHeads.add(new BoolSetting.Builder()
        .name("render-heads")
        .description("Draw player skin heads.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> headAtlas = sgHeads.add(new BoolSetting.Builder()
        .name("head-atlas")
        .description("Copy every face into one shared texture so all heads draw together instead of one draw per player. Faces that cannot be read are drawn the normal way.")
        .defaultValue(true)
        .visible(heads::get)
        .onChanged(v -> HeadAtlas.clear())
        .build()
    );

    public final Setting<Boolean> hatLayer = sgHeads.add(new BoolSetting.Builder()
        .name("hat-layer")
        .description("Draw the hat overlay on each face. With head-atlas on it is blended into the face and costs nothing extra; without it, it is a second quad per head.")
        .defaultValue(true)
        .visible(heads::get)
        .build()
    );

    public final Setting<Integer> headLimit = sgHeads.add(new IntSetting.Builder()
        .name("head-limit")
        .description("Stop drawing heads after this many. 0 draws them all. Players nearest the top keep theirs.")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 200)
        .visible(heads::get)
        .build()
    );

    public final Setting<Boolean> cacheSkins = sgHeads.add(new BoolSetting.Builder()
        .name("cache-skins")
        .description("Resolve each player's skin once per cache-time instead of once per head per frame.")
        .defaultValue(true)
        .visible(heads::get)
        .build()
    );

    public final Setting<Integer> maxRendered = sgPerformance.add(new IntSetting.Builder()
        .name("max-rendered")
        .description("How many players the tab list shows. Replaces vanilla's 80-player cap and Better Tab's tablist-size.")
        .defaultValue(1000)
        .min(1)
        .sliderRange(1, 2000)
        .build()
    );

    public final Setting<Integer> cacheTime = sgPerformance.add(new IntSetting.Builder()
        .name("cache-time")
        .description("Milliseconds to reuse the sorted player list and skins before rebuilding. 0 rebuilds every frame like vanilla.")
        .defaultValue(1000)
        .min(0)
        .sliderRange(0, 1000)
        .build()
    );

    public final Setting<Boolean> pingIcons = sgPerformance.add(new BoolSetting.Builder()
        .name("ping-icons")
        .description("Draw the vanilla ping bars. No effect while Better Tab's accurate-latency is on.")
        .defaultValue(false)
        .build()
    );

    private int tabHeldTicks;
    private boolean warnedNoHook;

    public TabOptimizer() {
        super(JJsTools.CATEGORY, "tablist-optimisations", "Makes the tab list cheap to draw on servers with large player lists.");
        INSTANCE = this;
    }

    /** Held directly instead of looked up, so this can never miss the live instance. */
    public static TabOptimizer get() {
        return INSTANCE;
    }

    public static boolean on() {
        TabOptimizer module = INSTANCE;
        return module != null && module.isActive();
    }

    @Override
    public void onActivate() {
        tabHeldTicks = 0;
        warnedNoHook = false;
    }

    @Override
    public void onDeactivate() {
        renderingTab = false;
        headsDrawn = 0;
        inHead = false;
        TabCache.invalidate();
        SkinCache.invalidate();
        LiteTab.invalidate();
        HeadAtlas.clear();
    }

    /** Meteor posts this after the vanilla GUI is flushed, so queued tab text lands on top of it. */
    @EventHandler
    private void onRender2D(Render2DEvent event) {
        LiteTab.drawQueuedText();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        TabCache.invalidate();
        SkinCache.invalidate();
        LiteTab.invalidate();
        HeadAtlas.clear();
    }

    /**
     * If you hold tab for a second and the vanilla tab list never draws, another
     * mod is drawing its own tab list and nothing here can affect it. Say so once
     * instead of silently doing nothing.
     */
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.getNetworkHandler() == null) {
            tabHeldTicks = 0;
            return;
        }

        boolean tabShouldDraw = mc.options.playerListKey.isPressed()
            && mc.currentScreen == null
            && !mc.options.hudHidden
            && mc.getNetworkHandler().getPlayerList().size() > 1;

        if (!tabShouldDraw) {
            tabHeldTicks = 0;
            return;
        }

        if (warnedNoHook || ++tabHeldTicks < 20) return;

        if (System.nanoTime() - lastHookNanos > 1_000_000_000L) {
            warnedNoHook = true;
            warning("The vanilla tab list is not being drawn, so there is nothing for Tablist Optimisations to change. Another mod is probably drawing its own tab list.");
        }
    }
}
