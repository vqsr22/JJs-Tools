package dev.jjstools.panel.panels;

import dev.jjstools.panel.Panel;
import dev.jjstools.panel.Prefs;
import dev.jjstools.panel.TextBox;
import dev.jjstools.panel.Ui;
import dev.jjstools.util.EncounterStore;
import meteordevelopment.meteorclient.systems.friends.Friend;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.item.Items;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Every player you have run into, from the Encounters module, as a table.
 *
 * Click a column's heading to sort by it, and again to flip the order. Search by name, add or
 * remove friends, hide everyone's coordinates for screenshots, or forget someone.
 */
public class EncountersPanel extends Panel {
    private enum Column { Player, Friend, Seen, First, Last, Coords, Dimension }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yy").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault());

    private final TextBox search = new TextBox("search");
    private final double[] scroll = { 0 };
    private boolean hideFriends;

    public EncountersPanel() {
        super("encounters", "encounters", Items.PLAYER_HEAD.getDefaultStack(), 260, 140);
    }

    @Override
    public String badge() {
        return String.valueOf(EncounterStore.size());
    }

    @Override
    public void draw(Ui ui, int x, int y, int w, int h) {
        Prefs.Data prefs = Prefs.get();
        int bh = Math.max(16, ui.lineH() + 7);

        // Options
        // Search and hide friends side by side, the same size.
        int half = (w - 4) / 2;
        ui.textBox(search, x, y, half, bh);
        ui.toggle(x + w - half, y, half, bh, "hide friends", hideFriends, () -> hideFriends = !hideFriends);

        int ty = y + bh + 4;

        // Every column, as narrow as its contents allow; only if the panel is too narrow even for
        // that are the least useful ones left off, dimension first.
        // Each column fits its heading with the sort arrow as well as its contents.
        int arrowW = ui.width(" " + Ui.UP);
        int forgetW = ui.lineH() + 5;
        int friendW = ui.width("friend") + arrowW + 10;
        int numW = Math.max(ui.width("seen") + arrowW, ui.width("00000")) + 10;
        int dateW = Math.max(ui.width("00/00/00"), ui.width("first") + arrowW) + 10;
        int coordW = Math.max(ui.width("-30000000, 320, -30000000"), ui.width("coords") + arrowW) + 10;
        int dimW = Math.max(ui.width("overworld"), ui.width("dim") + arrowW) + 10;
        int nameMin = ui.width("WWWWWWWWWW") + 22;

        boolean noCoords = prefs.hideCoords;
        List<Column> cols = new ArrayList<>(List.of(Column.Player, Column.Friend, Column.Seen, Column.First, Column.Last, Column.Coords, Column.Dimension));
        int fixed = friendW + numW + dateW * 2 + coordW + dimW + forgetW + 14;
        // Hidden coordinates take their column with them.
        if (noCoords) {
            cols.remove(Column.Coords);
            fixed -= coordW;
        }
        if (nameMin + fixed > w) {
            cols.remove(Column.Dimension);
            fixed -= dimW;
        }
        if (nameMin + fixed > w) {
            cols.remove(Column.First);
            fixed -= dateW;
        }
        if (nameMin + fixed > w && cols.remove(Column.Coords)) fixed -= coordW;
        // The name column only grows so far; any more room is shared out between the columns, so
        // they spread evenly across the panel instead of the names taking it all.
        int nameW = Math.max(nameMin, Math.min(w - fixed, nameMin + 50));
        int extra = Math.max(0, w - fixed - nameW) / Math.max(1, cols.size() - 1);
        java.util.Map<Column, Integer> widths = new java.util.EnumMap<>(Column.class);
        for (Column c : cols) {
            widths.put(c, switch (c) {
                case Player -> nameW;
                case Friend -> friendW + extra;
                case Seen -> numW + extra;
                case First, Last -> dateW + extra;
                case Coords -> coordW + extra;
                case Dimension -> dimW + extra;
            });
        }

        Column sort = column(prefs.encountersSort);
        int hx = x + 2;
        int headerH = ui.lineH() + 6;
        for (Column c : cols) {
            int cw = widths.get(c);
            String title = (c == Column.Dimension ? "dim" : c.name().toLowerCase(Locale.ROOT)) + (c == sort ? (prefs.encountersSortReverse ? " " + Ui.DOWN : " " + Ui.UP) : "");
            boolean hover = ui.hovered(hx, ty, cw, headerH);
            ui.text(title, hx, ty + 3, c == sort ? ui.accent() : hover ? ui.fg() : ui.fgDim());
            ui.onClick(hx, ty, cw, headerH, () -> {
                if (prefs.encountersSort.equals(c.name())) prefs.encountersSortReverse = !prefs.encountersSortReverse;
                else {
                    prefs.encountersSort = c.name();
                    prefs.encountersSortReverse = c == Column.Seen || c == Column.Last || c == Column.First;
                }
            });
            hx += cw;
        }

        // Rows
        String q = search.text.trim().toLowerCase(Locale.ROOT);
        // Filtered and sorted a couple of times a second, not every frame: there can be thousands.
        String key = q + "|" + sort + "|" + prefs.encountersSortReverse + "|" + hideFriends;
        long now = System.currentTimeMillis();
        if (!key.equals(listKey) || now - listAt > 500) {
            listKey = key;
            listAt = now;
            java.util.Set<String> friendNames = new java.util.HashSet<>();
            for (Friend f : Friends.get()) friendNames.add(f.getName().toLowerCase(Locale.ROOT));
            List<EncounterStore.Encounter> fresh = new ArrayList<>();
            for (EncounterStore.Encounter e : EncounterStore.all()) {
                if (e.name == null) continue;
                if (!q.isEmpty() && !e.name.toLowerCase(Locale.ROOT).contains(q)) continue;
                if (hideFriends && friendNames.contains(e.name.toLowerCase(Locale.ROOT))) continue;
                fresh.add(e);
            }
            fresh.sort(comparator(sort, prefs.encountersSortReverse, friendNames));
            cached = fresh;
        }
        List<EncounterStore.Encounter> list = cached;

        int ly = ty + headerH;
        int lh = h - (ly - y);
        int row = Math.max(16, ui.lineH() + 7);
        ui.well(x, ly, w, lh);
        ui.clip(x + 1, ly + 1, w - 2, lh - 2);
        int off = ui.scrollArea(x + 1, ly + 1, w - 2, lh - 2, list.size() * row + 2, scroll, row);
        int ry = ly + 1 - off;

        if (list.isEmpty()) ui.centredWrapped(q.isEmpty() ? "nobody yet" : "nobody matches", x, w, ly + lh / 2, ui.fgWellDim());

        for (EncounterStore.Encounter e : list) {
            if (ry + row < ly || ry > ly + lh) {
                ry += row;
                continue;
            }
            if (ui.hovered(x + 1, ry, w - 8, row)) ui.fill(x + 1, ry, w - 8, row, ui.rowHover());
            int cx = x + 2;
            int textY = ry + (row - ui.lineH()) / 2 + 1;
            Friend friend = Friends.get().get(e.name);
            for (Column c : cols) {
                switch (c) {
                    case Player -> {
                        boolean on = ui.online(e.name);
                        ui.dot(cx + 2, ry + (row - 6) / 2, 6, on ? Ui.GREEN : Ui.GREY_DOT);
                        int face = Math.min(12, row - 4);
                        ui.head(e.name, cx + 11, ry + (row - face) / 2, face);
                        ui.text(ui.fit(e.name, nameW - face - 16), cx + 13 + face, textY, on ? ui.fgWell() : ui.fgWellDim());
                        cx += widths.get(c);
                    }
                    case Friend -> {
                        int fb = row - 4;
                        ui.button(cx, ry + 2, fb, fb, friend == null ? "+" : "-", () -> {
                            if (friend == null) Friends.get().add(new Friend(e.name, e.id()));
                            else Friends.get().remove(friend);
                        });
                        if (friend != null) ui.text("*", cx + fb + 3, textY, Ui.GOLD);
                        cx += widths.get(c);
                    }
                    case Seen -> {
                        ui.text(String.valueOf(e.count), cx, textY, ui.fgWell());
                        cx += widths.get(c);
                    }
                    case First -> {
                        ui.text(DATE.format(Instant.ofEpochMilli(e.firstSeen)), cx, textY, ui.fgWellDim());
                        cx += widths.get(c);
                    }
                    case Last -> {
                        ui.text(DATE.format(Instant.ofEpochMilli(e.lastSeen)), cx, textY, ui.fgWell());
                        cx += widths.get(c);
                    }
                    case Coords -> {
                        ui.text(e.x + ", " + e.y + ", " + e.z, cx, textY, ui.fgWell());
                        cx += widths.get(c);
                    }
                    case Dimension -> {
                        ui.text(dimension(e.dimension), cx, textY, ui.fgWellDim());
                        cx += widths.get(c);
                    }
                }
            }
            boolean forget = ui.button(x + w - forgetW - 8, ry + 2, forgetW, row - 4, "x", () -> EncounterStore.remove(e));
            if (!forget && ui.hovered(x + 1, ry, w - 8, row)) {
                ui.tooltip(e.name + "  -  first seen " + FULL.format(Instant.ofEpochMilli(e.firstSeen)) + ", last seen " + FULL.format(Instant.ofEpochMilli(e.lastSeen)));
            }
            ry += row;
        }
        ui.unclip();
    }

    private static Column column(String name) {
        for (Column c : Column.values()) if (c.name().equals(name)) return c;
        return Column.Last;
    }

    private String listKey;
    private long listAt;
    private List<EncounterStore.Encounter> cached = List.of();

    private static Comparator<EncounterStore.Encounter> comparator(Column sort, boolean reverse, java.util.Set<String> friendNames) {
        Comparator<EncounterStore.Encounter> c = switch (sort) {
            case Player -> Comparator.comparing(e -> e.name.toLowerCase(Locale.ROOT));
            case Friend -> Comparator.comparing(e -> !friendNames.contains(e.name.toLowerCase(Locale.ROOT)));
            case Seen -> Comparator.comparingInt(e -> e.count);
            case First -> Comparator.comparingLong(e -> e.firstSeen);
            case Last -> Comparator.comparingLong(e -> e.lastSeen);
            case Coords -> Comparator.comparingDouble(e -> (double) e.x * e.x + (double) e.z * e.z);
            case Dimension -> Comparator.comparing(e -> e.dimension == null ? "" : e.dimension);
        };
        if (reverse) c = c.reversed();
        return c.thenComparing(e -> e.name.toLowerCase(Locale.ROOT));
    }

    private static String dimension(String id) {
        if (id == null) return "?";
        if (id.contains("nether")) return "nether";
        if (id.contains("end")) return "end";
        if (id.contains("overworld")) return "overworld";
        return id;
    }
}
