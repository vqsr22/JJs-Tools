package dev.jjstools.panel;

import dev.jjstools.panel.panels.BlockedPanel;
import dev.jjstools.panel.panels.ChatPanel;
import dev.jjstools.panel.panels.DeathsPanel;
import dev.jjstools.panel.panels.EncountersPanel;
import dev.jjstools.panel.panels.StashPanel;
import dev.jjstools.panel.panels.EnderPanel;
import dev.jjstools.panel.panels.PearlsPanel;
import dev.jjstools.panel.panels.LookupPanel;
import dev.jjstools.panel.panels.StatsPanel;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import dev.jjstools.modules.ControlPanel;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Comparator;
import java.util.List;

/**
 * The Control Panel itself: a bar of panel switches along the top, and the panels as windows
 * you can move by their title bars, size from their bottom right corners and close.
 *
 * Its own screen rather than one of Meteor's, so it is drawn with your resource pack's textures
 * and keeps the game running behind it. Where everything was, light or dark, and the text size
 * are kept from one opening to the next.
 */
public class ControlPanelScreen extends Screen {
    private static final int DOCK_MIN = 18, DOCK_MAX = 64;
    /** How tall the bar along the top is; set from the saved layout, and by dragging its bottom edge. */
    private int dockH = 26;
    /** Which edge the dock sits on: 0 top, 1 bottom. */
    private int dockSide;
    public static final String[] DOCK_SIDES = { "top", "bottom" };

    /** The part of the screen windows live in, everything but the dock. */
    private int areaX() { return 0; }
    private int areaY() { return dockSide == 0 ? dockH : 0; }
    private int areaW() { return vw; }
    private int areaH() { return vh - dockH; }

    /** The dock's own rectangle: x, y, width, height. */
    private int[] dockRect() {
        return dockSide == 1 ? new int[] { 0, vh - dockH, vw, dockH } : new int[] { 0, 0, vw, dockH };
    }

    static int sideIndex(String side) {
        for (int i = 0; i < DOCK_SIDES.length; i++) if (DOCK_SIDES[i].equals(side)) return i;
        return 0;
    }
    private static final int GRIP = 10;

    private final Ui ui = new Ui();
    private final List<Panel> panels = new ArrayList<>();

    private Panel dragging;
    private boolean resizing;
    private int grabX, grabY, startW, startH;

    public ControlPanelScreen() {
        super(Text.literal("Control Panel"));
        panels.add(new ChatPanel());
        panels.add(new StashPanel());
        panels.add(new EncountersPanel());
        panels.add(new BlockedPanel());
        panels.add(new DeathsPanel());
        panels.add(new StatsPanel());
        panels.add(new EnderPanel());
        panels.add(new PearlsPanel());
        panels.add(new LookupPanel());
    }

    /** The Control Panel's own scale on top of the game's, and the screen size it works to after it. */
    private float gs = 1.0F;
    private int vw, vh;

    private void measure() {
        gs = (float) dev.jjstools.modules.ControlPanel.guiScale();
        vw = Math.max(1, (int) (width / gs));
        vh = Math.max(1, (int) (height / gs));
    }

    @Override
    protected void init() {
        Ui.readPack();
        measure();
        Prefs.Data prefs = Prefs.get();
        dockH = Math.max(DOCK_MIN, Math.min(DOCK_MAX, prefs.dockHeight));
        dockSide = sideIndex(prefs.dockSide);

        int opened = 0;
        for (int i = 0; i < panels.size(); i++) {
            Panel p = panels.get(i);
            Prefs.Window win = prefs.windows.get(p.id);
            if (win == null) {
                win = defaultWindow(i);
                prefs.windows.put(p.id, win);
            }
            p.open = win.open;
            p.z = win.z;
            p.w = Math.max(p.minW, (int) (win.w * vw));
            p.h = Math.max(p.minH, (int) (win.h * vh));
            p.x = (int) (win.x * vw);
            p.y = (int) (win.y * vh);
            keepOnScreen(p);
            p.restore = null;
            if (win.maximised) maximise(p);
            if (p.open) {
                p.shown();
                // Opening the panel brings the windows in one after another.
                p.shownAt = dev.jjstools.util.Motion.now();
                p.delay = opened++ * dev.jjstools.util.Motion.STAGGER_MS;
            }
        }
    }

    /** Where each panel starts, as fractions of the screen: chat and stash on top, the rest along the bottom. */
    private static final double[][] DEFAULTS = {
        { 0.005, 0.065, 0.43, 0.52 },
        { 0.44, 0.065, 0.555, 0.52 },
        { 0.005, 0.595, 0.56, 0.40 },
        { 0.57, 0.595, 0.13, 0.40 },
        { 0.705, 0.595, 0.15, 0.40 },
        { 0.86, 0.595, 0.135, 0.40 },
        { 0.30, 0.20, 0.26, 0.21 },
        { 0.60, 0.20, 0.24, 0.34 },
        { 0.36, 0.18, 0.28, 0.40 },
    };

    private static Prefs.Window defaultWindow(int i) {
        Prefs.Window win = new Prefs.Window();
        win.x = DEFAULTS[i][0];
        win.y = DEFAULTS[i][1];
        win.w = DEFAULTS[i][2];
        win.h = DEFAULTS[i][3];
        win.z = i;
        return win;
    }

    /** Puts every panel back where it started, all of them open. */
    private boolean doubled;

    /** Puts one window back to the size it starts at, keeping where it is. */
    private void resetSize(Panel p) {
        int i = panels.indexOf(p);
        if (i < 0) return;
        Prefs.Window d = defaultWindow(i);
        p.restore = null;
        p.w = (int) (d.w * vw);
        p.h = (int) (d.h * vh);
        keepOnScreen(p);
        remember();
    }

    private void resetLayout() {
        Prefs.Data prefs = Prefs.get();
        prefs.windows.clear();
        prefs.dockHeight = 26;
        prefs.dockSide = "top";
        dockSide = 0;
        for (int i = 0; i < panels.size(); i++) prefs.windows.put(panels.get(i).id, defaultWindow(i));
        Prefs.save();
        init();
    }

    /** Makes a window fill the screen under the dock, keeping where it was to go back to. */
    private void maximise(Panel p) {
        p.restore = new int[] { p.x, p.y, p.w, p.h };
        fill(p);
    }

    /** Sets a window to the whole space beside the dock. */
    private void fill(Panel p) {
        p.x = areaX();
        p.y = areaY();
        p.w = areaW();
        p.h = areaH();
    }

    private void unmaximise(Panel p) {
        if (p.restore == null) return;
        p.x = p.restore[0];
        p.y = p.restore[1];
        p.w = p.restore[2];
        p.h = p.restore[3];
        p.restore = null;
        keepOnScreen(p);
    }

    private void keepOnScreen(Panel p) {
        p.w = Math.min(p.w, areaW());
        p.h = Math.min(p.h, areaH());
        p.x = Math.max(areaX(), Math.min(p.x, areaX() + areaW() - p.w));
        p.y = Math.max(areaY(), Math.min(p.y, areaY() + areaH() - p.h));
    }

    private void remember() {
        Prefs.Data prefs = Prefs.get();
        for (Panel p : panels) {
            Prefs.Window win = prefs.windows.computeIfAbsent(p.id, k -> new Prefs.Window());
            win.open = p.open;
            win.z = p.z;
            int[] r = p.restore != null ? p.restore : new int[] { p.x, p.y, p.w, p.h };
            win.maximised = p.restore != null;
            win.x = r[0] / (double) vw;
            win.y = r[1] / (double) vh;
            win.w = r[2] / (double) vw;
            win.h = r[3] / (double) vh;
        }
        Prefs.save();
    }

    private void toFront(Panel p) {
        int top = 0;
        for (Panel o : panels) top = Math.max(top, o.z);
        if (p.z != top) p.z = top + 1;
    }

    /** Whether a text box has the keyboard, so the open key types rather than closes. */
    public boolean typing() {
        return ui.focus != null;
    }

    /**
     * While a text box has the keyboard, every movement key is let go, so nothing walks, jumps,
     * sneaks or sprints from keys held as you started typing, or from GUI Move.
     */
    @Override
    public void tick() {
        super.tick();
        if (!typing() || client == null) return;
        var o = client.options;
        for (var key : new net.minecraft.client.option.KeyBinding[] { o.forwardKey, o.backKey, o.leftKey, o.rightKey, o.jumpKey, o.sneakKey, o.sprintKey }) {
            key.setPressed(false);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // The world stays in view behind the panels, only dimmed.
        context.fill(0, 0, width, height, 0x50000000);
    }

    @Override
    public void render(DrawContext context, int realX, int realY, float delta) {
        renderBackground(context, realX, realY, delta);
        Prefs.Data prefs = Prefs.get();
        // GUI scale sizes the panel, not its text: the text is drawn back down by the same amount.
        float scale = (float) (prefs.textScale / dev.jjstools.modules.ControlPanel.guiScale());

        // A change to the GUI scale setting while open lays everything out again.
        if (Math.abs(gs - (float) dev.jjstools.modules.ControlPanel.guiScale()) > 0.001F) {
            remember();
            init();
        }
        int mouseX = (int) (realX / gs);
        int mouseY = (int) (realY / gs);
        context.getMatrices().pushMatrix();
        context.getMatrices().scale(gs, gs);

        // Anything being dragged inside a panel, such as a column edge, follows the mouse.
        if (ui.drag != null) ui.drag.move(mouseX, mouseY);

        // Moving or sizing a window follows the mouse.
        if (dragging != null) {
            if (resizing) {
                dragging.w = Math.max(dragging.minW, startW + mouseX - grabX);
                dragging.h = Math.max(dragging.minH, startH + mouseY - grabY);
            } else {
                dragging.x = mouseX - grabX;
                dragging.y = mouseY - grabY;
            }
            keepOnScreen(dragging);
        }

        List<Panel> order = new ArrayList<>(panels);
        order.sort(Comparator.comparingInt(p -> p.z));

        // Only the window on top under the mouse sees it, so nothing behind lights up.
        Panel under = null;
        for (Panel p : order) {
            if (p.open && mouseX >= p.x && mouseY >= p.y && mouseX < p.x + p.w && mouseY < p.y + p.h) under = p;
        }
        int[] dr = dockRect();
        boolean overDock = mouseX >= dr[0] && mouseY >= dr[1] && mouseX < dr[0] + dr[2] && mouseY < dr[1] + dr[3];

        Ui.Theme theme = prefs.theme != null ? Ui.Theme.of(prefs.theme) : prefs.dark ? Ui.Theme.Dark : Ui.Theme.Light;
        ui.begin(context, overDock || settingsOpen ? mouseX : -1, overDock || settingsOpen ? mouseY : -1, scale, theme);
        ui.outlines = prefs.panelOutlines;
        drawDock(prefs);
        drawWatermark();

        for (Panel p : order) {
            boolean closing = !p.open && dev.jjstools.util.Motion.leaving(p.hiddenAt);
            if (!p.open && !closing) continue;
            boolean active = p.open && !overDock && !settingsOpen && p == under && (dragging == null || dragging == p);
            ui.mx = active ? mouseX : -1;
            ui.my = active ? mouseY : -1;
            // Growing out of its dock button as it opens, easing out, and shrinking back into it,
            // a fifth quicker, as it closes: from 95% of its size and a little way towards the button.
            double k = p.open ? dev.jjstools.util.Motion.enter(p.shownAt, p.delay) : dev.jjstools.util.Motion.exit(p.hiddenAt);
            if (k >= 1) {
                drawWindow(p);
                continue;
            }
            float cx = p.x + p.w / 2F, cy = p.y + p.h / 2F;
            float s = (float) (dev.jjstools.util.Motion.POPOVER_START + (1 - dev.jjstools.util.Motion.POPOVER_START) * k);
            float pull = (float) ((1 - k) * 0.15);
            var m = context.getMatrices();
            m.pushMatrix();
            m.translate(cx + (p.tabX - cx) * pull, cy + (p.tabY - cy) * pull);
            m.scale(s, s);
            m.translate(-cx, -cy);
            drawWindow(p);
            m.popMatrix();
        }

        applyEditedSetting();
        // The font previews are only kept while the font page is open.
        if (!(settingsOpen && settingsTab == 2)) UiFont.dropPreviews();
        boolean settingsClosing = !settingsOpen && dev.jjstools.util.Motion.leaving(settingsAt);
        if (settingsOpen || settingsClosing) {
            ui.mx = settingsOpen ? mouseX : -1;
            ui.my = settingsOpen ? mouseY : -1;
            // Out of its button and back into it, as the windows do.
            double k = settingsOpen ? dev.jjstools.util.Motion.enter(settingsAt, 0) : dev.jjstools.util.Motion.exit(settingsAt);
            if (k >= 1) {
                drawSettings(prefs, theme);
            } else {
                float s = (float) (dev.jjstools.util.Motion.POPOVER_START + (1 - dev.jjstools.util.Motion.POPOVER_START) * k);
                float cx = vw / 2F, cy = vh / 2F, pull = (float) ((1 - k) * 0.15);
                var m = context.getMatrices();
                m.pushMatrix();
                m.translate(cx + (settingsTabX - cx) * pull, cy + (settingsTabY - cy) * pull);
                m.scale(s, s);
                m.translate(-cx, -cy);
                drawSettings(prefs, theme);
                m.popMatrix();
            }
        }

        context.getMatrices().popMatrix();

        // Tooltips are placed on the real screen, after the scaling is undone.
        ui.mx = realX;
        ui.my = realY;
        ui.end();
    }

    private void drawDock(Prefs.Data prefs) {
        int[] r = dockRect();
        ui.fill(r[0], r[1], r[2], r[3], ui.panelColour());

        // The inner edge drags to make the bar thicker or thinner.
        int edge = 4;
        int[] e = dockSide == 1 ? new int[] { r[0], r[1], r[2], edge } : new int[] { r[0], r[1] + r[3] - edge, r[2], edge };
        boolean edgeHover = ui.hovered(e[0], e[1], e[2], e[3]);
        if (edgeHover || resizingDock) {
            ui.fill(r[0], dockSide == 1 ? r[1] : r[1] + r[3] - 1, r[2], 1, ui.accent());
        }
        ui.onClick(e[0], e[1], e[2], e[3], 0, b -> {
            resizingDock = true;
            ui.drag = (mx, my) -> setDockHeight(prefs, dockSide == 1 ? vh - my : my);
        });
        int size = dockH - 8;
        // Icons grow and shrink with the bar, and only show when turned on in settings.
        int icon = prefs.dockIcons ? Math.max(8, Math.min(40, size - 4)) : 0;
        // JJ's Tools' own icon, as in the module list, as big as the bar leaves room for.
        int logo = Math.max(10, dockH - 6);

        int x = 6;
        int y = r[1] + 4;
        ui.item(dev.jjstools.JJsTools.CATEGORY.icon, x, r[1] + (dockH - logo) / 2, logo);
        x += logo + 8;

        // The panel buttons are all as wide as the widest, so they line up evenly.
        int tabW = 0;
        for (Panel p : panels) tabW = Math.max(tabW, ui.tabWidth(p.title, icon) + 6);
        for (Panel p : panels) {
            p.tabX = x + tabW / 2F;
            p.tabY = y + size / 2F;
            ui.tab(x, y, tabW, size, p.icon, icon, p.title, p.open, () -> toggleWindow(p));
            x += tabW + 3;
        }

        // Right side: everything else lives in settings.
        int gw = ui.width("settings") + 24;
        settingsTabX = vw - 6 - gw / 2F;
        settingsTabY = y + size / 2F;
        ui.toggle(vw - 6 - gw, y, gw, size, "settings", settingsOpen, () -> {
            settingsOpen = !settingsOpen;
            settingsAt = dev.jjstools.util.Motion.now();
        });
    }

    private static String watermark;

    /** The name and version, small in the bottom right corner, under every window. */
    private void drawWatermark() {
        if (watermark == null) {
            String version = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("jjs-tools")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("");
            int plus = version.indexOf('+');
            if (plus > 0) version = version.substring(0, plus);
            watermark = ("jj's tools " + version).trim();
        }
        int x = areaX() + areaW() - ui.smallWidth(watermark) - 4;
        int y = areaY() + areaH() - ui.smallH() - 3;
        ui.small(watermark, x, y, ui.fgDim());
    }

    private double settingsAt = -1e9;
    private float settingsTabX, settingsTabY;

    private void toggleWindow(Panel p) {
        p.open = !p.open;
        if (p.open) {
            toFront(p);
            p.shown();
            p.shownAt = dev.jjstools.util.Motion.now();
            p.delay = 0;
        } else {
            p.hiddenAt = dev.jjstools.util.Motion.now();
        }
        remember();
    }

    /** Moves the dock to another edge of the screen, moving windows out of its way. */
    private void setDockSide(Prefs.Data prefs, int side) {
        if (side == dockSide) return;
        dockSide = side;
        prefs.dockSide = DOCK_SIDES[side];
        for (Panel p : panels) {
            if (p.restore != null) fill(p);
            else keepOnScreen(p);
        }
        remember();
    }

    private boolean resizingDock;

    /** Makes the bar along the top this tall, moving windows out from under it. */
    private void setDockHeight(Prefs.Data prefs, int height) {
        int h = Math.max(DOCK_MIN, Math.min(DOCK_MAX, height));
        if (h == dockH) return;
        dockH = h;
        prefs.dockHeight = h;
        for (Panel p : panels) {
            if (p.restore != null) {
                fill(p);
            } else {
                keepOnScreen(p);
            }
        }
    }

    private boolean settingsOpen;

    // Settings

    private int settingsTab;
    private long clearChatAsked;
    private final double[] settingsScroll = { 0 };
    private int settingsContentH;
    private int clipTop, clipBottom;
    /** Text boxes for the module's settings, and the one being typed in, applied when you leave it. */
    private final Map<Setting<?>, TextBox> settingBoxes = new HashMap<>();
    private TextBox editingBox;
    private Setting<?> editingSetting;
    /** The keybind waiting for a key to be pressed. */
    private KeybindSetting capturing;

    private interface Control {
        void draw(int x, int y, int w);
    }

    /**
     * Settings in a box over the panel, laid out like monkeytype's: a name and what it does on
     * the left, its control on the right. General holds the panel's own settings, themes every
     * theme, and control panel the module's settings from Meteor, the same ones, so changing them
     * here changes them there.
     */
    private void drawSettings(Prefs.Data prefs, Ui.Theme current) {
        ui.onClick(0, 0, vw, vh, -1, b -> settingsOpen = false);
        ui.fill(areaX(), areaY(), areaW(), areaH(), 0x60000000);

        int w = Math.min(areaW() - 24, Math.max(320, (int) (areaW() * 0.72)));
        int h = areaH() - 24;
        int x = areaX() + (areaW() - w) / 2;
        int y = areaY() + 12;
        ui.panel(x, y, w, h);
        ui.onClick(x, y, w, h, -1, b -> {});
        int pad = 12;
        int bh = Math.max(16, ui.lineH() + 7);

        String[] tabs = { "general", "themes", "font", "control panel" };
        int tw = (w - pad * 2 - 4 * (tabs.length - 1)) / tabs.length;
        for (int i = 0; i < tabs.length; i++) {
            int tab = i;
            ui.choice(x + pad + i * (tw + 4), y + pad, tw, bh, tabs[i], settingsTab == i, () -> {
                settingsTab = tab;
                settingsScroll[0] = 0;
            });
        }

        int cy = y + pad + bh + 10;
        int ch = y + h - pad - cy;
        if (settingsTab == 1) {
            drawThemeGrid(prefs, current, x + pad, cy, w - pad * 2, ch);
            return;
        }
        if (settingsTab == 2) {
            drawFontGrid(prefs, x + pad, cy, w - pad * 2, ch, bh);
            return;
        }
        clipTop = cy;
        clipBottom = cy + ch;
        ui.clip(x + 2, cy, w - 4, ch);
        int off = ui.scrollArea(x + 2, cy, w - 4, ch, settingsContentH, settingsScroll, 24);
        int ry = cy - off;
        int rx = x + pad;
        int rw = w - pad * 2 - 8;
        ry = settingsTab == 0 ? generalRows(prefs, rx, ry, rw, bh) : moduleRows(rx, ry, rw, bh);
        settingsContentH = ry + off - cy + 6;
        ui.unclip();
    }

    private int generalRows(Prefs.Data prefs, int x, int y, int w, int bh) {
        y = row(x, y, w, "hide coords", "Hides every coordinate in the panel, for screenshots and streams.", bh,
            (cx, cy, cw) -> onOff(cx, cy, cw, bh, prefs.hideCoords, v -> {
                prefs.hideCoords = v;
                remember();
            }));
        y = row(x, y, w, "dock position", "Which edge of the screen the dock sits on.", bh, (cx, cy, cw) -> {
            int gap = 4;
            int bw = (cw - gap) / 2;
            ui.choice(cx, cy, bw, bh, DOCK_SIDES[0], dockSide == 0, () -> setDockSide(prefs, 0));
            ui.choice(cx + bw + gap, cy, cw - bw - gap, bh, DOCK_SIDES[1], dockSide == 1, () -> setDockSide(prefs, 1));
        });
        y = row(x, y, w, "dock icons", "Shows each panel's item on its button in the dock.", bh,
            (cx, cy, cw) -> onOff(cx, cy, cw, bh, prefs.dockIcons, v -> {
                prefs.dockIcons = v;
                remember();
            }));
        y = row(x, y, w, "panel outlines", "Draws a thin outline around each panel, so you can tell which is on top where they overlap.", bh,
            (cx, cy, cw) -> onOff(cx, cy, cw, bh, prefs.panelOutlines, v -> {
                prefs.panelOutlines = v;
                remember();
            }));
        y = row(x, y, w, "text size", "How big the panel's text is, separate from the GUI scale.", bh, (cx, cy, cw) -> {
            int step = bh;
            ui.stepButton(cx, cy, step, false, () -> {
                prefs.textScale = Math.max(0.5, Math.round((prefs.textScale - 0.1) * 10) / 10.0);
                remember();
            });
            ui.round(cx + step + 4, cy, cw - step * 2 - 8, bh, 3, ui.buttonShade());
            ui.textCentred(Math.round(prefs.textScale * 100) + "%", cx + cw / 2, cy + (bh - ui.lineH()) / 2 + 1, ui.fg());
            ui.stepButton(cx + cw - step, cy, step, true, () -> {
                prefs.textScale = Math.min(2.0, Math.round((prefs.textScale + 0.1) * 10) / 10.0);
                remember();
            });
        });
        y = row(x, y, w, "reset layout", "Puts every panel back where it started, at its first size.", bh,
            (cx, cy, cw) -> ui.choice(cx, cy, cw, bh, "reset", false, this::resetLayout));
        // Asks to be clicked twice, since it cannot be undone.
        boolean sure = System.currentTimeMillis() - clearChatAsked < 3000;
        y = row(x, y, w, "clear chat history", "Deletes every whisper saved in the chat panel, with everyone. This can't be undone.", bh,
            (cx, cy, cw) -> ui.choice(cx, cy, cw, bh, sure ? "sure? click again" : "clear", sure, () -> {
                if (System.currentTimeMillis() - clearChatAsked < 3000) {
                    ChatLog.clearAll();
                    clearChatAsked = 0;
                } else {
                    clearChatAsked = System.currentTimeMillis();
                }
            }));
        return y;
    }

    private int moduleRows(int x, int y, int w, int bh) {
        ControlPanel module = Modules.get().get(ControlPanel.class);
        if (module == null) return y;
        for (SettingGroup group : module.settings) {
            boolean any = false;
            for (Setting<?> s : group) if (s.isVisible()) any = true;
            if (!any) continue;
            if (y + ui.lineH() > clipTop && y < clipBottom) ui.text(group.name.toLowerCase(java.util.Locale.ROOT), x, y + 6, ui.accent());
            y += ui.lineH() + 14;
            for (Setting<?> s : group) {
                if (!s.isVisible()) continue;
                y = settingRow(s, x, y, w, bh);
            }
            y += 6;
        }
        return y;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private int settingRow(Setting<?> s, int x, int y, int w, int bh) {
        String title = s.name.replace('-', ' ');
        if (s instanceof BoolSetting b) {
            return row(x, y, w, title, s.description, bh, (cx, cy, cw) -> onOff(cx, cy, cw, bh, b.get(), b::set));
        }
        if (s instanceof EnumSetting<?> e) {
            Object[] values = ((Enum<?>) e.get()).getDeclaringClass().getEnumConstants();
            int perRow = Math.min(values.length, 3);
            int rows = (values.length + perRow - 1) / perRow;
            int controlH = rows * (bh + 4) - 4;
            return row(x, y, w, title, s.description, controlH, (cx, cy, cw) -> {
                int ew = (cw - (perRow - 1) * 4) / perRow;
                for (int i = 0; i < values.length; i++) {
                    Object v = values[i];
                    ui.choice(cx + (i % perRow) * (ew + 4), cy + (i / perRow) * (bh + 4), ew, bh,
                        v.toString().toLowerCase(java.util.Locale.ROOT), v == e.get(), () -> e.parse(v.toString()));
                }
            });
        }
        if (s instanceof KeybindSetting k) {
            return row(x, y, w, title, s.description, bh, (cx, cy, cw) -> {
                String label = capturing == k ? "press a key..." : k.get().toString().toLowerCase(java.util.Locale.ROOT);
                ui.choice(cx, cy, cw, bh, label, capturing == k, () -> capturing = capturing == k ? null : k);
            });
        }
        if (s instanceof ColorSetting c) {
            return row(x, y, w, title, s.description, bh, (cx, cy, cw) -> {
                SettingColor col = c.get();
                ui.round(cx, cy, bh, bh, 3, (col.a << 24) | (col.r << 16) | (col.g << 8) | col.b);
                TextBox box = boxFor(s, col.r + " " + col.g + " " + col.b + " " + col.a);
                ui.textBox(box, cx + bh + 4, cy, cw - bh - 4, bh);
            });
        }
        // Numbers and text: typed in, and applied when you press Enter or click away.
        return row(x, y, w, title, s.description, bh, (cx, cy, cw) -> ui.textBox(boxFor(s, s.toString()), cx, cy, cw, bh));
    }

    /** A setting's text box, kept showing its value whenever it is not being typed in. */
    private TextBox boxFor(Setting<?> s, String value) {
        TextBox box = settingBoxes.computeIfAbsent(s, k -> {
            TextBox t = new TextBox("");
            t.max = 512;
            t.onEnter = () -> {
                apply(k, t);
                ui.focus = null;
            };
            return t;
        });
        if (ui.focus == box) {
            editingBox = box;
            editingSetting = s;
        } else if (!box.text.equals(value)) {
            box.set(value);
        }
        return box;
    }

    private void applyEditedSetting() {
        if (editingBox != null && ui.focus != editingBox) {
            apply(editingSetting, editingBox);
            editingBox = null;
            editingSetting = null;
        }
    }

    private static void apply(Setting<?> s, TextBox box) {
        String text = box.text.trim();
        if (!text.equals(s.toString())) s.parse(text);
    }

    /** One row: a name and what it does on the left, its control on the right. Gives where the next row starts. */
    private int row(int x, int y, int w, String title, String description, int controlH, Control control) {
        int lw = (int) (w * 0.55);
        int cx = x + lw + 14;
        int cw = w - lw - 14;
        List<String> lines = description == null || description.isBlank() ? List.of() : ui.wrap(description, Math.round(lw / 0.75F));
        int textH = ui.lineH() + 3 + lines.size() * (ui.smallH() + 2);
        int h = Math.max(textH, controlH) + 14;
        if (y + h > clipTop && y < clipBottom) {
            ui.text(title, x, y, ui.fgDim());
            int ly = y + ui.lineH() + 3;
            for (String l : lines) {
                ui.small(l, x, ly, ui.fg());
                ly += ui.smallH() + 2;
            }
            control.draw(cx, y + Math.max(0, (textH - controlH) / 2), cw);
        }
        return y + h;
    }

    /** Off and on, side by side, as monkeytype's toggles are. */
    private void onOff(int x, int y, int w, int bh, boolean value, java.util.function.Consumer<Boolean> set) {
        int half = (w - 4) / 2;
        ui.choice(x, y, half, bh, "off", !value, () -> set.accept(false));
        ui.choice(x + w - half, y, half, bh, "on", value, () -> set.accept(true));
    }

    private final double[] themeScroll = { 0 };
    private final double[] fontScroll = { 0 };

    /**
     * Every font, as monkeytype's font family setting lays them out: same-sized buttons, yours in
     * the main colour. Fonts other than Roboto Mono download the first time they are picked.
     */
    private void drawFontGrid(Prefs.Data prefs, int x, int y, int w, int h, int bh) {
        List<String> all = PanelFonts.choices();
        int top = y;
        ui.text("font family", x, top, ui.fgDim());
        top += ui.lineH() + 3;
        for (String line : ui.wrap("The font the panel is drawn in. Meteor uses the font set in Meteor's config. Each font downloads once, the first time this page is open, so its name can be shown in it.", Math.round(w / 0.75F))) {
            ui.small(line, x, top, ui.fg());
            top += ui.smallH() + 2;
        }
        top += 8;

        int widest = 0;
        for (String f : all) widest = Math.max(widest, ui.width(f));
        int gap = 4;
        int cols = Math.max(1, Math.min(6, (w - 8 + gap) / (widest + 30 + gap)));
        int cellW = (w - 8 - gap * (cols - 1)) / cols;
        int rows = (all.size() + cols - 1) / cols;
        int gh = y + h - top;
        int contentH = rows * (bh + gap);
        ui.clip(x - 10, top, w + 20, gh);
        int off = ui.scrollArea(x - 10, top, w + 20, gh, contentH, fontScroll, bh + gap);
        String chosen = prefs.font == null ? PanelFonts.MINECRAFT : prefs.font;
        for (int i = 0; i < all.size(); i++) {
            String f = all.get(i);
            int cx = x + (i % cols) * (cellW + gap);
            int cy = top + (i / cols) * (bh + gap) - off;
            if (cy + bh < top || cy > top + gh) continue;
            // Each name in its own font, as soon as that font is here.
            UiFont.Face face = UiFont.preview(f);
            String state = PanelFonts.state(f);
            Runnable pick = () -> {
                prefs.font = f;
                PanelFonts.retry(f);
                remember();
            };
            if (state.equals("downloading")) ui.choice(cx, cy, cellW, bh, "downloading...", f.equals(chosen), pick);
            else if (state.equals("failed")) ui.choice(cx, cy, cellW, bh, f + " (failed)", f.equals(chosen), pick);
            else ui.choiceIn(face, cx, cy, cellW, bh, f, f.equals(chosen), pick);
        }
        ui.unclip();
    }

    /**
     * Every theme, laid out as monkeytype does: a grid of same-sized buttons, each in its own
     * theme's background with its name in its main colour, lightest to darkest, dark and light
     * first. Hovering one shows its colours as dots; yours has a ring.
     */
    private void drawThemeGrid(Prefs.Data prefs, Ui.Theme current, int x, int y, int w, int h) {
        List<Ui.Theme> all = Ui.Theme.ordered();
        int widest = 0;
        for (Ui.Theme t : all) widest = Math.max(widest, ui.width(t.title));
        int cellW = widest + 40;
        int cellH = Math.max(16, ui.lineH() + 9);
        int gap = 4;
        int cols = Math.max(1, Math.min(5, (w - 8 + gap) / (cellW + gap)));
        cellW = (w - 8 - gap * (cols - 1)) / cols;

        int rows = (all.size() + cols - 1) / cols;
        int contentH = rows * (cellH + gap);
        ui.clip(x - 10, y, w + 20, h);
        int off = ui.scrollArea(x - 10, y, w + 20, h, contentH, themeScroll, cellH + gap);
        int ringColour = ui.accent();
        for (int i = 0; i < all.size(); i++) {
            Ui.Theme t = all.get(i);
            int cx = x + (i % cols) * (cellW + gap);
            int cy = y + (i / cols) * (cellH + gap) - off;
            if (cy + cellH < y || cy > y + h) continue;
            boolean hover = ui.hovered(cx, cy, cellW, cellH);
            boolean on = t == current;
            int cw = cellW;
            ui.withTheme(t, () -> {
                if (on) {
                    // An even ring, a little thicker than a line, on every side.
                    ui.ring(cx, cy, cw, cellH, 4, 3, ringColour, ui.panelColour());
                } else {
                    ui.round(cx, cy, cw, cellH, 4, ui.panelColour());
                }
                int name = t.nameColour() != 0 ? t.nameColour() : ui.fg();
                ui.textCentred(t.title, cx + cw / 2, cy + (cellH - ui.lineH()) / 2 + 1, name);
                // The palette only on the theme that is picked, as monkeytype shows it.
                if (on) {
                    int d = 6;
                    int dx = cx + cw - 7 - d * 3 - 6;
                    int dy = cy + (cellH - d) / 2;
                    ui.dot(dx, dy, d, ui.accent());
                    ui.dot(dx + d + 3, dy, d, ui.fgDim());
                    ui.dot(dx + (d + 3) * 2, dy, d, ui.fg());
                }
            });
            ui.onClick(cx, cy, cellW, cellH, 0, b -> {
                prefs.theme = t.name();
                prefs.dark = t != Ui.Theme.Light;
                remember();
            });
        }
        ui.unclip();
    }

    private void drawWindow(Panel p) {
        ui.panel(p.x, p.y, p.w, p.h);

        // Clicking anywhere in a window brings it to the front, before whatever was clicked acts.
        ui.onClick(p.x, p.y, p.w, p.h, -1, b -> toFront(p));

        int barH = Math.max(16, ui.lineH() + 7);
        int ty = p.y + (barH - ui.lineH()) / 2 + 1;
        ui.text(p.title, p.x + 7, ty, ui.fgDim());

        int cs = Math.max(7, barH / 2);
        int closeX = p.x + p.w - cs - 5;
        int maxX = closeX - cs - 4;

        // Title bar: drag to move. Dragging a window that fills the screen puts it back to its
        // own size first, under the mouse.
        ui.onClick(p.x, p.y, maxX - 3 - p.x, barH, 0, b -> {
            toFront(p);
            if (p.restore != null) {
                int along = ui.mx - p.x;
                double frac = p.w > 0 ? along / (double) p.w : 0.5;
                unmaximise(p);
                p.x = ui.mx - (int) Math.round(frac * p.w);
                p.y = areaY();
                keepOnScreen(p);
            }
            dragging = p;
            resizing = false;
            grabX = ui.mx - p.x;
            grabY = ui.my - p.y;
        });

        // Maximise and close, like macOS's green and red buttons.
        ui.maximiseButton(p.id, maxX, p.y + (barH - cs) / 2, cs, p.restore != null, () -> {
            if (p.restore != null) unmaximise(p);
            else maximise(p);
            toFront(p);
            remember();
        });
        ui.closeButton(p.id, closeX, p.y + (barH - cs) / 2, cs, () -> {
            p.open = false;
            p.hiddenAt = dev.jjstools.util.Motion.now();
            remember();
        });

        int cx = p.x + 5;
        int cy = p.y + barH + 4;
        int cw = p.w - 10;
        int ch = p.h - barH - 9;
        ui.clip(cx, cy, cw, ch);
        try {
            p.draw(ui, cx, cy, cw, ch);
        } catch (Exception e) {
            ui.text("Something went wrong drawing this panel: " + e.getClass().getSimpleName(), cx, cy + 4, Ui.RED);
        }
        ui.unclip();

        // Resize grip, bottom right.
        int gx = p.x + p.w - GRIP - 1;
        int gy = p.y + p.h - GRIP - 1;
        int grip = Ui.alpha(ui.fgDim(), ui.hovered(gx, gy, GRIP, GRIP) ? 1F : 0.5F);
        for (int i = 0; i < 3; i++) ui.fill(gx + GRIP - 3 - i * 3, gy + GRIP - 1, 2, 1, grip);
        for (int i = 0; i < 3; i++) ui.fill(gx + GRIP - 1, gy + GRIP - 3 - i * 3, 1, 2, grip);
        ui.fill(gx + GRIP - 5, gy + GRIP - 5, 2, 2, grip);
        // Right-clicking or double-clicking the grip puts the window back to its first size.
        ui.onClick(gx, gy, GRIP + 1, GRIP + 1, 1, b -> resetSize(p));
        ui.onClick(gx, gy, GRIP + 1, GRIP + 1, 0, b -> {
            toFront(p);
            if (doubled) {
                resetSize(p);
                return;
            }
            // Resizing a window that fills the screen makes the new size its own.
            p.restore = null;
            dragging = p;
            resizing = true;
            grabX = ui.mx;
            grabY = ui.my;
            startW = p.w;
            startH = p.h;
        });
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        this.doubled = doubled;
        if (click.button() == 0) ui.down = true;
        TextBox before = ui.focus;
        ui.focus = null;
        double x = click.x() / gs;
        double y = click.y() / gs;
        ui.mx = (int) x;
        ui.my = (int) y;
        boolean used = ui.click(x, y, click.button());
        if (!used && before != null && ui.focus == null) ui.focus = null;
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (click.button() == 0) ui.down = false;
        if (ui.drag != null) {
            ui.drag = null;
            resizingDock = false;
            remember();
        }
        if (dragging != null) {
            dragging = null;
            resizing = false;
            remember();
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        ui.scroll(mouseX / gs, mouseY / gs, verticalAmount);
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (capturing != null) {
            // Escape clears the bind; any other key becomes it.
            capturing.set(input.key() == GLFW.GLFW_KEY_ESCAPE ? Keybind.none() : Keybind.fromKeys(input.key(), input.modifiers()));
            capturing = null;
            return true;
        }
        if (ui.focus != null) {
            if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
                ui.focus = null;
                return true;
            }
            if (ui.focus.key(input.key(), input.modifiers())) return true;
            // Letters and the like arrive as typed characters; keep them from doing anything else.
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (ui.focus != null) {
            ui.focus.type(input.asString());
            return true;
        }
        return super.charTyped(input);
    }

    @Override
    public void removed() {
        remember();
        UiFont.dropPreviews();
        ChatLog.open = null;
        ChatLog.save();
        super.removed();
    }
}
