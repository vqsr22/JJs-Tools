package dev.jjstools.panel.panels;

import dev.jjstools.panel.Panel;
import dev.jjstools.panel.SessionStats;
import dev.jjstools.panel.Ui;
import net.minecraft.item.Items;

/**
 * This session and all time side by side: how long you have played, how far you have walked,
 * flown and ridden, blocks placed and broken, deaths, totems popped, items picked up, players
 * seen and whispers sent and received.
 */
public class StatsPanel extends Panel {
    private final double[] scroll = { 0 };

    public StatsPanel() {
        super("stats", "session stats", Items.CLOCK.getDefaultStack(), 170, 120);
    }

    @Override
    public void draw(Ui ui, int x, int y, int w, int h) {
        SessionStats.Numbers s = SessionStats.session;
        SessionStats.Numbers t = SessionStats.total();

        String[][] rows = {
            { "playtime", time(s.ticks), time(t.ticks) },
            { "walked", distance(s.walked), distance(t.walked) },
            { "flown", distance(s.flown), distance(t.flown) },
            { "ridden", distance(s.ridden), distance(t.ridden) },
            { "placed", Ui.grouped(s.placed), Ui.grouped(t.placed) },
            { "broken", Ui.grouped(s.broken), Ui.grouped(t.broken) },
            { "deaths", Ui.grouped(s.deaths), Ui.grouped(t.deaths) },
            { "totems popped", Ui.grouped(s.totems), Ui.grouped(t.totems) },
            { "picked up", Ui.grouped(s.pickedUp), Ui.grouped(t.pickedUp) },
            { "players seen", Ui.grouped(s.playersSeen), Ui.grouped(t.playersSeen) },
            { "whispers in", Ui.grouped(s.whispersIn), Ui.grouped(t.whispersIn) },
            { "whispers out", Ui.grouped(s.whispersOut), Ui.grouped(t.whispersOut) },
            { "chat sent", Ui.grouped(s.chatSent), Ui.grouped(t.chatSent) },
            { "sessions", "", Ui.grouped(t.sessions) },
        };

        String where = SessionStats.sessionServer == null || SessionStats.sessionServer.isEmpty() ? "not on a server" : SessionStats.sessionServer
            + (SessionStats.sessionStart > 0 ? "  \u00B7  " + ChatPanel.ago(SessionStats.sessionStart) : "");
        ui.text(ui.fit(where, w), x, y + 2, ui.fgDim());

        int ly = y + ui.lineH() + 6;
        int lh = h - (ly - y);
        int row = ui.lineH() + 6;
        ui.well(x, ly, w, lh);

        // The numbers come first: each column is as wide as its widest value, from the right, and
        // the names get whatever room is left, cut short with dots if they do not fit.
        int allW = ui.width("all time");
        int sessionW = ui.width("session");
        for (String[] r : rows) {
            sessionW = Math.max(sessionW, ui.width(r[1]));
            allW = Math.max(allW, ui.width(r[2]));
        }
        int allRight = x + w - 10;
        int sessionRight = allRight - allW - 12;
        int labelRoom = sessionRight - sessionW - 8 - (x + 6);

        ui.clip(x + 1, ly + 1, w - 2, lh - 2);
        int off = ui.scrollArea(x + 1, ly + 1, w - 2, lh - 2, (rows.length + 1) * row + 4, scroll, row);
        int cy = ly + 3 - off;
        ui.textRight("session", sessionRight, cy, ui.accent());
        ui.textRight("all time", allRight, cy, ui.accent());
        cy += row;
        for (int i = 0; i < rows.length; i++) {
            if (i % 2 == 1) ui.fill(x + 1, cy - 2, w - 8, row, ui.rowHover());
            ui.text(ui.fit(rows[i][0], labelRoom), x + 6, cy, ui.fgWellDim());
            ui.textRight(rows[i][1], sessionRight, cy, ui.fgWell());
            ui.textRight(rows[i][2], allRight, cy, ui.fgWell());
            cy += row;
        }
        ui.unclip();
    }

    private static String time(long ticks) {
        long minutes = ticks / 20 / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        if (days > 0) return days + "d " + (hours % 24) + "h";
        if (hours > 0) return hours + "h " + (minutes % 60) + "m";
        return minutes + "m";
    }

    private static String distance(double blocks) {
        if (blocks >= 1000) return Ui.decimal(blocks / 1000.0) + " km";
        return Math.round(blocks) + " m";
    }
}
