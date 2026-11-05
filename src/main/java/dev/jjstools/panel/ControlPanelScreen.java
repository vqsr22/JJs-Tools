package dev.jjstools.panel;

import dev.jjstools.panel.panels.BlockedPanel;
import dev.jjstools.panel.panels.ChatPanel;
import dev.jjstools.panel.panels.DeathsPanel;
import dev.jjstools.panel.panels.EncountersPanel;
import dev.jjstools.panel.panels.StashPanel;
import dev.jjstools.panel.panels.StatsPanel;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
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
    private static final int DOCK_H = 26;
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
            if (p.open) p.shown();
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
    private void resetLayout() {
        Prefs.Data prefs = Prefs.get();
        prefs.windows.clear();
        for (int i = 0; i < panels.size(); i++) prefs.windows.put(panels.get(i).id, defaultWindow(i));
        Prefs.save();
        init();
    }

    private void keepOnScreen(Panel p) {
        p.w = Math.min(p.w, vw);
        p.h = Math.min(p.h, vh - DOCK_H);
        p.x = Math.max(0, Math.min(p.x, vw - p.w));
        p.y = Math.max(DOCK_H, Math.min(p.y, vh - p.h));
    }

    private void remember() {
        Prefs.Data prefs = Prefs.get();
        for (Panel p : panels) {
            Prefs.Window win = prefs.windows.computeIfAbsent(p.id, k -> new Prefs.Window());
            win.open = p.open;
            win.z = p.z;
            win.x = p.x / (double) vw;
            win.y = p.y / (double) vh;
            win.w = p.w / (double) vw;
            win.h = p.h / (double) vh;
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
        boolean overDock = mouseY < DOCK_H;

        Ui.Theme theme = prefs.theme != null ? Ui.Theme.of(prefs.theme) : prefs.dark ? Ui.Theme.Dark : Ui.Theme.Light;
        ui.begin(context, overDock || themesOpen ? mouseX : -1, overDock || themesOpen ? mouseY : -1, scale, theme);
        drawDock(prefs);

        for (Panel p : order) {
            if (!p.open) continue;
            boolean active = !overDock && !themesOpen && p == under && (dragging == null || dragging == p);
            ui.mx = active ? mouseX : -1;
            ui.my = active ? mouseY : -1;
            drawWindow(p);
        }

        if (themesOpen) {
            ui.mx = mouseX;
            ui.my = mouseY;
            drawThemes(prefs, theme);
        }

        context.getMatrices().popMatrix();

        // Tooltips are placed on the real screen, after the scaling is undone.
        ui.mx = realX;
        ui.my = realY;
        ui.end();
    }

    private void drawDock(Prefs.Data prefs) {
        ui.panel(-1, -1, vw + 2, DOCK_H + 1);
        int x = 6;
        int y = 4;
        int size = DOCK_H - 8;

        // JJ's Tools' own icon, as in the module list.
        ui.item(dev.jjstools.JJsTools.CATEGORY.icon, x, y + (size - 16) / 2);
        if (ui.hovered(x, y, 16, size)) ui.tooltip("JJ's Tools Control Panel");
        x += 22;

        for (Panel p : panels) {
            int w = 22 + ui.width(p.title) + 8;
            ui.toggle(x, y, w, size, "", p.open, () -> {
                p.open = !p.open;
                if (p.open) {
                    toFront(p);
                    p.shown();
                }
                remember();
            });
            ui.item(p.icon, x + 3, y + (size - 16) / 2);
            int ty = y + (size - ui.lineH()) / 2 + 1;
            ui.text(p.title, x + 22, ty, p.open ? ui.fg() : ui.fgDim());
            x += w + 2;
        }

        // Right side: coordinates, layout, light or dark, and text size.
        int right = vw - 6;
        Ui.Theme current = prefs.theme != null ? Ui.Theme.of(prefs.theme) : prefs.dark ? Ui.Theme.Dark : Ui.Theme.Light;
        int bw = 0;
        for (Ui.Theme t : Ui.Theme.values()) bw = Math.max(bw, ui.width(t.title + " " + Ui.DOWN));
        bw += 14;
        themeX = right - bw;
        themeW = bw;
        ui.button(right - bw, y, bw, size, current.title + " " + (themesOpen ? Ui.UP : Ui.DOWN), () -> themesOpen = !themesOpen);
        right -= bw + 4;
        int rw = ui.width("Reset layout") + 14;
        ui.button(right - rw, y, rw, size, "Reset layout", this::resetLayout);
        right -= rw + 4;
        int hw = ui.width("Hide coords") + 14;
        boolean hc = ui.toggle(right - hw, y, hw, size, "Hide coords", prefs.hideCoords, () -> {
            prefs.hideCoords = !prefs.hideCoords;
            remember();
        });
        if (hc) ui.tooltip("Hides every coordinate in the Control Panel, for screenshots and streams.");
        right -= hw + 8;

        ui.button(right - size, y, size, size, "+", () -> {
            prefs.textScale = Math.min(2.0, Math.round((prefs.textScale + 0.1) * 10) / 10.0);
            remember();
        });
        right -= size + 2;
        String pct = Math.round(prefs.textScale * 100) + "%";
        int pw = ui.width("100%") + 8;
        ui.textCentred(pct, right - pw / 2, y + (size - ui.lineH()) / 2 + 1, ui.fg());
        right -= pw + 2;
        ui.button(right - size, y, size, size, "-", () -> {
            prefs.textScale = Math.max(0.5, Math.round((prefs.textScale - 0.1) * 10) / 10.0);
            remember();
        });
        right -= size + 4;
        ui.textRight("Text", right, y + (size - ui.lineH()) / 2 + 1, ui.fgDim());
    }

    private boolean themesOpen;
    private int themeX, themeW;

    /** The theme list, open below its button, drawn over everything. */
    private void drawThemes(Prefs.Data prefs, Ui.Theme current) {
        ui.onClick(0, 0, vw, vh, -1, b -> themesOpen = false);
        int row = DOCK_H - 8;
        Ui.Theme[] all = Ui.Theme.values();
        int h = all.length * row + 4;
        int x = themeX;
        int y = DOCK_H;
        ui.panel(x, y, themeW, h);
        int ry = y + 2;
        for (Ui.Theme t : all) {
            ui.toggle(x + 2, ry, themeW - 4, row, t.title, t == current, () -> {
                prefs.theme = t.name();
                prefs.dark = t != Ui.Theme.Light;
                themesOpen = false;
                remember();
            });
            ry += row;
        }
    }

    private void drawWindow(Panel p) {
        ui.panel(p.x, p.y, p.w, p.h);

        // Clicking anywhere in a window brings it to the front, before whatever was clicked acts.
        ui.onClick(p.x, p.y, p.w, p.h, -1, b -> toFront(p));

        int barH = Math.max(16, ui.lineH() + 7);
        int ty = p.y + (barH - ui.lineH()) / 2 + 1;
        ui.text(p.title, p.x + 6, ty, ui.fg());
        ui.divider(p.x + 1, p.y + barH, p.w - 2);

        // Title bar: drag to move.
        ui.onClick(p.x, p.y, p.w - barH, barH, 0, b -> {
            toFront(p);
            dragging = p;
            resizing = false;
            grabX = ui.mx - p.x;
            grabY = ui.my - p.y;
        });

        // Close
        int cs = Math.max(7, barH / 2);
        boolean closing = ui.closeButton(p.x + p.w - cs - 5, p.y + (barH - cs) / 2, cs, () -> {
            p.open = false;
            remember();
        });
        if (closing) ui.tooltip("Close " + p.title);

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
        int grip = ui.hovered(gx, gy, GRIP, GRIP) ? 0xFFFFFFFF : 0x90FFFFFF;
        for (int i = 0; i < 3; i++) ui.fill(gx + GRIP - 3 - i * 3, gy + GRIP - 1, 2, 1, grip);
        for (int i = 0; i < 3; i++) ui.fill(gx + GRIP - 1, gy + GRIP - 3 - i * 3, 1, 2, grip);
        ui.fill(gx + GRIP - 5, gy + GRIP - 5, 2, 2, grip);
        ui.onClick(gx, gy, GRIP + 1, GRIP + 1, 0, b -> {
            toFront(p);
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
        if (ui.drag != null) {
            ui.drag = null;
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
        ChatLog.open = null;
        ChatLog.save();
        super.removed();
    }
}
