package dev.jjstools.panel.panels;

import dev.jjstools.panel.PearlThrower;
import dev.jjstools.panel.PearlThrower.Bot;
import dev.jjstools.panel.Panel;
import dev.jjstools.panel.Prefs;
import dev.jjstools.panel.TextBox;
import dev.jjstools.panel.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Pearlbots and their stasis chambers, each under the name of its base.
 *
 * Pull asks the pearlbot to pull your pearl and resets the chamber you land at; reset resets
 * the chamber you are standing at. Both are done by PearlThrower, which finds
 * the chamber itself, since a base's chambers are many and which one is yours is not fixed.
 * Below the list, the chosen pearlbot's names, pull message and pearl container are set, the
 * container from what you are looking at.
 */
public class PearlsPanel extends Panel {
    private final TextBox base = new TextBox("base name");
    private final TextBox bot = new TextBox("pearlbot username");
    private final TextBox message = new TextBox("pull message (eg. load base or warp)");
    private final double[] scroll = { 0 };
    private Bot selected;

    public PearlsPanel() {
        super("pearls", "pearls", Items.ENDER_PEARL.getDefaultStack(), 170, 150);
        base.max = 32;
        bot.max = 16;
        base.onChange = () -> edit(b -> b.base = base.text.trim());
        bot.onChange = () -> edit(b -> b.bot = bot.text.trim());
        message.onChange = () -> edit(b -> b.message = message.text.trim());
    }

    private interface Change {
        void apply(Bot b);
    }

    private boolean filling;

    private void edit(Change c) {
        if (filling || selected == null) return;
        c.apply(selected);
        PearlThrower m = PearlThrower.get();
        m.save();
    }

    private void select(Bot b) {
        selected = b;
        filling = true;
        base.set(b == null ? "" : b.base);
        bot.set(b == null ? "" : b.bot);
        message.set(b == null ? "" : b.message);
        filling = false;
    }

    @Override
    public String badge() {
        PearlThrower m = PearlThrower.get();
        return String.valueOf(m.bots().size());
    }

    @Override
    public void draw(Ui ui, int x, int y, int w, int h) {
        PearlThrower m = PearlThrower.get();
        List<Bot> bots = m.bots();
        if (selected != null && !bots.contains(selected)) select(null);
        if (selected == null && !bots.isEmpty()) {
            int last = m.lastUsed();
            select(bots.get(last >= 0 && last < bots.size() ? last : 0));
        }

        int bh = Math.max(16, ui.lineH() + 7);
        int gap = 3;

        // Top: what it is doing, and a new pearlbot.
        int nw = ui.width("new") + 24;
        String status = m.status();
        ui.text(ui.fit(status, w - nw - 6), x, y + (bh - ui.lineH()) / 2 + 1, m.running() ? ui.accent() : ui.fgDim());
        if (m.running()) ui.button(x + w - nw, y, nw, bh, "stop", true, m::cancel);
        else ui.button(x + w - nw, y, nw, bh, "new", true, () -> select(m.add()));

        // The editor for the chosen pearlbot takes the bottom; the list gets the rest.
        int editH = selected == null ? 0 : bh * 3 + gap * 2 + ui.smallH() + 6;
        int ly = y + bh + gap;
        int lh = h - (ly - y) - (editH > 0 ? editH + gap : 0);
        int row = bh + 4;
        ui.well(x, ly, w, lh);
        ui.clip(x + 1, ly + 1, w - 2, lh - 2);
        int off = ui.scrollArea(x + 1, ly + 1, w - 2, lh - 2, bots.size() * row + 2, scroll, row);
        int cy = ly + 1 - off;
        if (bots.isEmpty()) ui.centredWrapped("no pearlbots yet", x, w, ly + lh / 2, ui.fgWellDim());
        int bw = Math.max(ui.width("pull"), ui.width("reset")) + 20;
        boolean connected = MinecraftClient.getInstance().player != null;
        for (Bot b : bots) {
            Bot it = b;
            boolean busy = m.current() == b;
            if (b == selected) ui.fill(x + 1, cy, w - 2, row, ui.rowHover());
            else if (ui.hovered(x + 1, cy, w - 8, row)) ui.fill(x + 1, cy, w - 8, row, ui.rowHover());
            int buttons = bw * 2 + gap;
            int bx = x + w - 9 - buttons;
            ui.onClick(x + 1, cy, bx - x - 2, row, () -> select(it));
            int ty = cy + (row - ui.lineH()) / 2 + 1;
            String label = ui.fit(b.label(), bx - x - 12);
            ui.text(label, x + 6, ty, busy ? ui.accent() : ui.fgWell());
            if (!b.bot.isBlank() && !b.base.isBlank()) {
                int lx = x + 6 + ui.width(label) + 5;
                ui.small(ui.fitSmall(b.bot, bx - lx - 4), lx, ty + ui.lineH() - ui.smallH(), ui.fgWellDim());
            }
            boolean ready = connected && !m.running();
            ui.button(bx, cy + 2, bw, row - 4, "pull", ready && !b.bot.isBlank() && !b.message.isBlank(), () -> m.run(it, true));
            ui.button(bx + bw + gap, cy + 2, bw, row - 4, "reset", ready, () -> m.run(it, false));
            cy += row;
        }
        ui.unclip();

        if (selected == null) return;
        Bot b = selected;
        int ey = ly + lh + gap;
        int half = (w - gap) / 2;
        ui.textBox(base, x, ey, half, bh);
        ui.textBox(bot, x + half + gap, ey, w - half - gap, bh);
        ey += bh + gap;
        ui.textBox(message, x, ey, w, bh);
        ey += bh + gap;

        // The pearl container is set from what you look at.
        MinecraftClient mc = MinecraftClient.getInstance();
        int cw = (w - gap) / 2;
        ui.button(x, ey, cw, bh, "set pearl chest", connected, () -> {
            BlockPos at = looking(mc);
            // Only something it can open and take a pearl out of.
            var be = at == null ? null : mc.world.getBlockEntity(at);
            if (be instanceof net.minecraft.block.entity.ChestBlockEntity
                || be instanceof net.minecraft.block.entity.BarrelBlockEntity
                || be instanceof net.minecraft.block.entity.ShulkerBoxBlockEntity) {
                b.chest = Bot.of(at);
                m.save();
            }
        });
        if (ui.hovered(x, ey, cw, bh)) ui.tooltip("Look at pearl container and set");
        ui.button(x + cw + gap, ey, w - cw - gap, bh, "delete", !m.running(), () -> {
            m.remove(b);
            select(null);
        });
        ey += bh + gap;

        String where = "pearl chest " + coords(b.chest, Prefs.get().hideCoords, "not set");
        ui.small(ui.fitSmall(where, w), x, ey + 2, ui.fgDim());
    }

    private static String coords(int[] p, boolean hide, String none) {
        if (p == null) return none;
        if (hide) return "set";
        return p[0] + ", " + p[1] + ", " + p[2];
    }

    /** The block you were looking at when the panel opened, since the panel itself takes the mouse. */
    private static BlockPos looking(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return null;
        HitResult hit = mc.crosshairTarget;
        if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK) return b.getBlockPos();
        return null;
    }
}
