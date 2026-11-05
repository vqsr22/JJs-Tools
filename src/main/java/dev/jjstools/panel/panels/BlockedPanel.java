package dev.jjstools.panel.panels;

import dev.jjstools.panel.BlockedList;
import dev.jjstools.panel.Panel;
import dev.jjstools.panel.TextBox;
import dev.jjstools.panel.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Who you have blocked on this server, with /ignore.
 *
 * Refresh asks the server for its list and reads the reply back; blocking or unblocking here,
 * or with /ignore anywhere, keeps it up to date. 2b2t's /ignore is a toggle, so the same command
 * both blocks and unblocks.
 */
public class BlockedPanel extends Panel {
    private final TextBox name = new TextBox("Name to block");
    private final TextBox search = new TextBox("Search");
    private final double[] scroll = { 0 };
    private boolean askedOnOpen;

    public BlockedPanel() {
        super("blocked", "Blocked", Items.BARRIER.getDefaultStack(), 150, 140);
        name.max = 16;
        name.onEnter = this::block;
    }

    @Override
    public String badge() {
        return String.valueOf(BlockedList.current().names.size());
    }

    @Override
    public void shown() {
        // Asked for once per opening if it has never been fetched on this server.
        if (!askedOnOpen && BlockedList.current().refreshed == 0 && MinecraftClient.getInstance().getNetworkHandler() != null) {
            askedOnOpen = true;
            BlockedList.refresh();
        }
    }

    private void block() {
        String n = name.text.trim();
        if (!n.matches("[A-Za-z0-9_]{1,16}")) return;
        if (!BlockedList.isBlocked(n)) BlockedList.toggle(n);
        name.set("");
    }

    @Override
    public void draw(Ui ui, int x, int y, int w, int h) {
        int bh = Math.max(16, ui.lineH() + 7);
        BlockedList.Server server = BlockedList.current();
        boolean connected = MinecraftClient.getInstance().getNetworkHandler() != null;

        int bw = ui.width("Block") + 14;
        ui.textBox(name, x, y, w - bw - 3, bh);
        ui.button(x + w - bw, y, bw, bh, "Block", connected && !name.text.isBlank(), this::block);

        int ry = y + bh + 3;
        int rw = ui.width("Refresh") + 14;
        ui.textBox(search, x, ry, w - rw - 3, bh);
        String progress = BlockedList.progress();
        boolean hover = ui.button(x + w - rw, ry, rw, bh, progress != null ? progress : "Refresh", connected && !BlockedList.refreshing(), BlockedList::refresh);
        if (hover) ui.tooltip("Runs /ignorelist and reads every page of the reply, keeping it out of your chat.");

        int iy = ry + bh + 3;
        String when = BlockedList.refreshing() ? "Reading the list from the server..."
            : server.refreshed == 0 ? "Not fetched from the server yet" : "Fetched " + ChatPanel.ago(server.refreshed) + " ago";
        ui.small(ui.fitSmall(when + " from " + server.address, w), x, iy, ui.fgDim());

        String q = search.text.trim().toLowerCase(Locale.ROOT);
        List<String> list = new ArrayList<>();
        for (String n : server.names) if (q.isEmpty() || n.toLowerCase(Locale.ROOT).contains(q)) list.add(n);

        int ly = iy + ui.smallH() + 4;
        int lh = h - (ly - y);
        int row = Math.max(16, ui.lineH() + 7);
        ui.well(x, ly, w, lh);
        ui.clip(x + 1, ly + 1, w - 2, lh - 2);
        int off = ui.scrollArea(x + 1, ly + 1, w - 2, lh - 2, list.size() * row + 2, scroll, row);
        int cy = ly + 1 - off;
        if (list.isEmpty()) ui.centredWrapped(server.names.isEmpty() ? "Nobody blocked" : "Nobody matches", x, w, ly + lh / 2, ui.fgWellDim());
        for (String n : list) {
            if (ui.hovered(x + 1, cy, w - 8, row)) ui.fill(x + 1, cy, w - 8, row, ui.rowHover());
            boolean on = ui.online(n);
            ui.dot(x + 5, cy + (row - 6) / 2, 6, on ? Ui.GREEN : Ui.GREY_DOT);
            int uw = ui.width("Unblock") + 12;
            String kind = server.kinds == null ? null : server.kinds.get(n.toLowerCase(Locale.ROOT));
            int face = Math.min(12, row - 4);
            ui.head(n, x + 14, cy + (row - face) / 2, face);
            int nameX = x + 14 + face + 4;
            String shown = ui.fit(n, w - uw - (nameX - x) - 16 - (kind == null ? 0 : ui.smallWidth(kind) + 4));
            int ny = cy + (row - ui.lineH()) / 2 + 1;
            ui.text(shown, nameX, ny, ui.fgWell());
            if (kind != null) ui.small(kind, nameX + ui.width(shown) + 4, ny + ui.lineH() - ui.smallH(), ui.fgWellDim());
            ui.button(x + w - uw - 9, cy + 1, uw, row - 2, "Unblock", connected, () -> BlockedList.toggle(n));
            cy += row;
        }
        ui.unclip();
    }
}
