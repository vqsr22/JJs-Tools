package dev.jjstools.panel.panels;

import dev.jjstools.modules.ControlPanel;
import dev.jjstools.panel.ChatLog;
import dev.jjstools.panel.Panel;
import dev.jjstools.panel.Prefs;
import dev.jjstools.panel.TextBox;
import dev.jjstools.panel.Ui;
import dev.jjstools.util.EncounterStore;
import meteordevelopment.meteorclient.systems.friends.Friend;
import meteordevelopment.meteorclient.systems.friends.Friends;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Whispers, laid out like a messenger.
 *
 * Down the left, everyone you have whispered with, friends first and then everyone else, with a
 * green light for anyone on now and grey for anyone not, and a count of unread messages. Sort by
 * name or by most recent, either way round. In the middle, the conversation with whoever is
 * picked, every message with the time it was sent in your own time zone, and a box to reply.
 * Down the right, quick messages: one click whispers a saved line, "load base" or "warp", to
 * whoever is picked, for pearl pulling and the like. Right-click one to change or remove it.
 */
public class ChatPanel extends Panel {
    private enum Sort { Name, Recent }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy");

    private final TextBox search = new TextBox("search");
    private final TextBox message = new TextBox("message");
    private final TextBox newPerson = new TextBox("new chat");

    private final double[] contactScroll = { 0 };
    private final double[] chatScroll = { 0 };
    private boolean stickToBottom = true;

    /** The conversation laid out into lines, kept until it or the room for it changes. */
    /**
     * One thing in the conversation: a day heading, or a message as a bubble of wrapped lines,
     * with its time shown under it when it ends a run from the same side.
     */
    private record Line(boolean separator, boolean incoming, List<String> text, String time, int width, boolean showTime, boolean runEnds) {}

    private String layoutFor;
    private int layoutCount = -1;
    private int layoutWidth = -1;
    private float layoutScale = -1;
    private final List<Line> lines = new ArrayList<>();

    public ChatPanel() {
        super("chat", "chat", Items.WRITABLE_BOOK.getDefaultStack(), 300, 160);
        message.max = 240;
        message.onEnter = this::send;
        newPerson.max = 16;
        newPerson.onEnter = () -> {
            String name = newPerson.text.trim();
            if (name.matches("[A-Za-z0-9_]{1,16}")) {
                ChatLog.contact(name);
                select(name);
                newPerson.set("");
            }
        };
    }

    @Override
    public String badge() {
        int unread = ChatLog.unreadTotal();
        return unread > 0 ? unread + " unread" : null;
    }

    @Override
    public void shown() {
        String open = Prefs.get().openChat;
        if (open != null) select(open);
    }

    private String selected() {
        return Prefs.get().openChat;
    }

    private void select(String name) {
        Prefs.get().openChat = name;
        ChatLog.open = name;
        ChatLog.markRead(name);
        stickToBottom = true;
        layoutFor = null;
    }

    @Override
    public void draw(Ui ui, int x, int y, int w, int h) {
        Prefs.Data prefs = Prefs.get();
        int gap = 6;
        int minMid = 140;
        int leftW = Math.max(80, Math.min((int) (w * prefs.chatLeft), w - minMid - gap));
        int midW = w - leftW - gap;

        drawContacts(ui, x, y, leftW, h);
        drawConversation(ui, x + leftW + gap, y, midW, h);

        // The gap between the columns can be dragged to make them wider or narrower.
        int panelX = x;
        int panelW = w;
        ui.dragHandle(x + leftW, y, gap, h, (mx, my) ->
            prefs.chatLeft = Math.max(0.1, Math.min(0.6, (mx - panelX) / (double) panelW)));

        String open = selected();
        if (open != null) ChatLog.open = open;
    }

    // People

    private void drawContacts(Ui ui, int x, int y, int w, int h) {
        Prefs.Data prefs = Prefs.get();
        int bh = Math.max(16, ui.lineH() + 7);

        Sort sort = "Name".equals(prefs.chatSort) ? Sort.Name : Sort.Recent;
        // Name runs A to Z with the arrow up; Recent runs newest first with the arrow down.
        String arrow = prefs.chatSortReverse ? " " + Ui.UP : " " + Ui.DOWN;
        String nameArrow = prefs.chatSortReverse ? " " + Ui.DOWN : " " + Ui.UP;
        int half = (w - 2) / 2;
        ui.toggle(x, y, half, bh, sort == Sort.Name ? "name" + nameArrow : "name", sort == Sort.Name, () -> {
            if (sort == Sort.Name) prefs.chatSortReverse = !prefs.chatSortReverse;
            else {
                prefs.chatSort = "Name";
                prefs.chatSortReverse = false;
            }
        });
        ui.toggle(x + half + 2, y, w - half - 2, bh, sort == Sort.Recent ? "recent" + arrow : "recent", sort == Sort.Recent, () -> {
            if (sort == Sort.Recent) prefs.chatSortReverse = !prefs.chatSortReverse;
            else {
                prefs.chatSort = "Recent";
                prefs.chatSortReverse = false;
            }
        });

        int sy = y + bh + 3;
        // Search and hide offline side by side, the same size.
        int hw = w - half - 2;
        ui.textBox(search, x, sy, half, bh);
        ui.toggle(x + w - hw, sy, hw, bh, "hide offline", prefs.chatHideOffline, () -> {
            prefs.chatHideOffline = !prefs.chatHideOffline;
            Prefs.save();
        });
        int ly = sy + bh + 3;
        int listH = h - (ly - y) - bh - 3;

        // Worked out a few times a second rather than every frame; who is on changes slowly.
        String filter = search.text.trim().toLowerCase(Locale.ROOT);
        String key = filter + "|" + sort + "|" + prefs.chatSortReverse + "|" + prefs.chatHideOffline + "|" + selected() + "|" + prefs.chatPinned;
        long now = System.currentTimeMillis();
        if (!key.equals(peopleKey) || now - peopleAt > 250) {
            peopleKey = key;
            peopleAt = now;
            buildPeople(ui, prefs, filter, sort);
        }
        List<ChatLog.Contact> pinned = cachedPinned;
        List<ChatLog.Contact> friends = cachedFriends;
        List<ChatLog.Contact> others = cachedOthers;

        int row = Math.max(16, ui.lineH() + 8);
        int head = ui.lineH() + 6;
        int contentH = (pinned.isEmpty() ? 0 : head + pinned.size() * row)
            + head + Math.max(1, friends.size()) * row + head + Math.max(1, others.size()) * row;

        ui.well(x, ly, w, listH);
        ui.clip(x + 1, ly + 1, w - 2, listH - 2);
        int off = ui.scrollArea(x + 1, ly + 1, w - 2, listH - 2, contentH, contactScroll, row);
        int cy = ly + 1 - off;

        if (!pinned.isEmpty()) {
            cy = section(ui, x, cy, w, "pinned", head);
            for (ChatLog.Contact c : pinned) cy = cy + row < ly || cy > ly + listH ? cy + row : person(ui, x, cy, w - 7, row, c);
        }

        cy = section(ui, x, cy, w, "friends", head);
        if (friends.isEmpty()) {
            ui.text("no friends", x + 6, cy + (row - ui.lineH()) / 2, ui.fgWellDim());
            cy += row;
        }
        for (ChatLog.Contact c : friends) cy = cy + row < ly || cy > ly + listH ? cy + row : person(ui, x, cy, w - 7, row, c);

        cy = section(ui, x, cy, w, "others", head);
        if (others.isEmpty()) {
            ui.text("nobody yet", x + 6, cy + (row - ui.lineH()) / 2, ui.fgWellDim());
            cy += row;
        }
        for (ChatLog.Contact c : others) cy = cy + row < ly || cy > ly + listH ? cy + row : person(ui, x, cy, w - 7, row, c);
        ui.unclip();

        ui.textBox(newPerson, x, y + h - bh, w, bh);
    }

    private String peopleKey;
    private long peopleAt;
    private List<ChatLog.Contact> cachedPinned = List.of();
    private List<ChatLog.Contact> cachedFriends = List.of();
    private List<ChatLog.Contact> cachedOthers = List.of();

    /** Everyone you have talked to, and every friend whether or not you have, split and sorted. */
    private void buildPeople(Ui ui, Prefs.Data prefs, String filter, Sort sort) {
        Map<String, ChatLog.Contact> people = new LinkedHashMap<>();
        for (ChatLog.Contact c : ChatLog.all()) people.put(c.name.toLowerCase(Locale.ROOT), c);
        for (Friend f : Friends.get()) {
            people.computeIfAbsent(f.getName().toLowerCase(Locale.ROOT), k -> {
                ChatLog.Contact c = new ChatLog.Contact();
                c.name = f.getName();
                return c;
            });
        }

        // Pinned people you have never talked to still show, so a pin never loses anyone.
        for (String name : prefs.chatPinned) {
            people.computeIfAbsent(name, k -> {
                ChatLog.Contact c = new ChatLog.Contact();
                c.name = name;
                return c;
            });
        }

        List<ChatLog.Contact> pinned = new ArrayList<>();
        List<ChatLog.Contact> friends = new ArrayList<>();
        List<ChatLog.Contact> others = new ArrayList<>();
        for (ChatLog.Contact c : people.values()) {
            if (!filter.isEmpty() && !c.name.toLowerCase(Locale.ROOT).contains(filter)) continue;
            boolean isPinned = prefs.chatPinned.contains(c.name.toLowerCase(Locale.ROOT));
            if (prefs.chatHideOffline && !isPinned && !ui.online(c.name) && !c.name.equalsIgnoreCase(selected())) continue;
            (isPinned ? pinned : Friends.get().get(c.name) != null ? friends : others).add(c);
        }

        Comparator<ChatLog.Contact> order = sort == Sort.Name
            ? Comparator.comparing(c -> c.name.toLowerCase(Locale.ROOT))
            : Comparator.comparingLong((ChatLog.Contact c) -> c.last).reversed().thenComparing(c -> c.name.toLowerCase(Locale.ROOT));
        if (prefs.chatSortReverse) order = order.reversed();
        // Unread messages first, then whoever is on now, then everyone else, each in the chosen order.
        order = Comparator.comparing((ChatLog.Contact c) -> c.unread <= 0)
            .thenComparing((ChatLog.Contact c) -> !ui.online(c.name)).thenComparing(order);
        pinned.sort(order);
        friends.sort(order);
        others.sort(order);

        cachedPinned = pinned;
        cachedFriends = friends;
        cachedOthers = others;
    }

    private int section(Ui ui, int x, int y, int w, String title, int h) {
        ui.text(title, x + 5, y + (h - ui.lineH()) / 2 + 1, ui.accent());
        return y + h;
    }

    private int person(Ui ui, int x, int y, int w, int row, ChatLog.Contact c) {
        boolean selected = c.name.equalsIgnoreCase(selected());
        boolean hover = ui.hovered(x + 1, y, w, row);
        if (selected) ui.fill(x + 1, y, w, row, ui.rowSelected());
        else if (hover) ui.fill(x + 1, y, w, row, ui.rowHover());

        boolean on = ui.online(c.name);
        int dot = 6;
        ui.dot(x + 5, y + (row - dot) / 2, dot, on ? Ui.GREEN : Ui.GREY_DOT);
        int face = Math.min(12, row - 4);
        ui.head(c.name, x + 14, y + (row - face) / 2, face);

        int nameX = x + 14 + face + 4;
        int right = x + w - 3;

        // The pin only shows while you hover the row. Like monkeytype's buttons, it swaps colour
        // at once: the text colour under the mouse, the dim colour while held down, and the
        // accent colour when they are pinned.
        String lower = c.name.toLowerCase(Locale.ROOT);
        boolean isPinned = Prefs.get().chatPinned.contains(lower);
        int pinSize = Math.min(12, row - 4);
        int pinX = right - pinSize, pinY = y + (row - pinSize) / 2;
        if (hover) {
            boolean overPin = ui.hovered(pinX - 2, y, pinSize + 4, row);
            int colour = overPin && ui.down ? ui.fgWellDim()
                : overPin ? ui.fgWell()
                : isPinned ? ui.accent()
                : ui.fgWellDim();
            ui.pin(pinX, pinY, pinSize, colour);
            right -= pinSize + 4;
        }

        if (c.unread > 0) {
            // A red circle with the count, stretched into a pill when the number is too wide.
            String n = c.unread > 99 ? "99+" : String.valueOf(c.unread);
            int d = ui.lineH() + 3;
            int bw = Math.max(d, ui.width(n) + 6);
            int by = y + (row - d) / 2;
            ui.round(right - bw, by, bw, d, d / 2F, 0xFFE0453A);
            ui.text(n, right - bw + (bw - ui.width(n)) / 2, by + (d - ui.lineH()) / 2 + 1, 0xFFFFFFFF);
            right -= bw + 3;
        } else if (c.last > 0) {
            String ago = ago(c.last);
            ui.textRight(ago, right, y + (row - ui.lineH()) / 2 + 1, ui.fgWellDim());
            right -= ui.width(ago) + 4;
        }
        ui.text(ui.fit(c.name, right - nameX), nameX, y + (row - ui.lineH()) / 2 + 1, on ? ui.fgWell() : ui.fgWellDim());

        ui.onClick(x + 1, y, w, row, () -> select(c.name));
        // After the row, so a click on the pin pins rather than opening the chat.
        if (hover) ui.onClick(pinX - 2, y, pinSize + 4, row, () -> {
            List<String> pins = Prefs.get().chatPinned;
            if (!pins.remove(lower)) pins.add(lower);
            Prefs.save();
            peopleKey = null;
        });
        return y + row;
    }

    // Conversation

    private void drawConversation(Ui ui, int x, int y, int w, int h) {
        String name = selected();
        int bh = Math.max(16, ui.lineH() + 7);

        if (name == null) {
            ui.well(x, y, w, h);
            ui.centredWrapped("pick someone", x, w, y + h / 2, ui.fgWellDim());
            return;
        }

        // Header: who, whether they are on, and quick actions.
        boolean on = ui.online(name);
        int hh = bh + 2;
        ui.head(name, x, y + (hh - 16) / 2, 16);
        ui.text(name, x + 20, y + 2, ui.fg());
        String status;
        if (on) status = "online";
        else {
            long seen = lastSeen(name);
            status = seen > 0 ? "last seen " + ago(seen) + " ago" : "offline";
        }
        int sy = y + 2 + ui.lineH() + 2;
        ui.dot(x + 20, sy + (ui.smallH() - 4) / 2, 4, on ? Ui.GREEN : Ui.GREY_DOT);
        ui.small(status, x + 27, sy, ui.fgDim());

        boolean friend = Friends.get().get(name) != null;
        int fw = Math.max(ui.width("unfriend"), ui.width("add friend")) + 20;
        ui.button(x + w - fw, y, fw, bh, friend ? "unfriend" : "add friend", () -> {
            Friend f = Friends.get().get(name);
            if (f != null) Friends.get().remove(f);
            else Friends.get().add(new Friend(name, null));
        });

        int my = y + hh + 6;
        int mh = h - (my - y) - bh - 4;
        ui.well(x, my, w, mh);

        ChatLog.Contact contact = ChatLog.find(name);
        List<ChatLog.Message> messages = contact == null ? List.of() : contact.messages;
        int inner = w - 16;
        layout(ui, name, messages, inner);

        int lh = ui.lineH();
        int lineStep = lh + 1;
        int contentH = 6;
        for (Line l : lines) contentH += height(ui, l, lineStep);
        contentH += 4;

        // Kept at the newest message unless you have scrolled up to read back.
        int max = Math.max(0, contentH - (mh - 2));
        if (stickToBottom) chatScroll[0] = max;
        ui.clip(x + 1, my + 1, w - 2, mh - 2);
        int off = ui.scrollArea(x + 1, my + 1, w - 2, mh - 2, contentH, chatScroll, lineStep * 3);
        stickToBottom = chatScroll[0] >= max - 1;

        // Bubbles as iMessage has them: yours on the right in the theme's main colour, theirs on
        // the left in the button shade, round-cornered, with a tiny time under each run.
        int cy = my + 1 + 6 - off;
        int padX = 7, padY = 4;
        for (Line l : lines) {
            int lhgt = height(ui, l, lineStep);
            if (cy + lhgt < my || cy > my + mh) {
                cy += lhgt;
                continue;
            }
            if (l.separator()) {
                String day = l.text().get(0);
                ui.tiny(day, x + (w - ui.tinyWidth(day)) / 2, cy + 3, ui.fgWellDim());
            } else {
                int bubbleW = l.width() + padX * 2;
                int bubbleH = l.text().size() * lineStep + padY * 2 - 1;
                int bx = l.incoming() ? x + 6 : x + w - 10 - bubbleW;
                int fill = l.incoming() ? ui.buttonShade() : ui.accent();
                int ink = l.incoming() ? ui.fg() : ui.panelColour();
                ui.round(bx, cy, bubbleW, bubbleH, Math.min(8, bubbleH / 2F), fill);
                int ty = cy + padY + 1;
                for (String line : l.text()) {
                    ui.text(line, bx + padX, ty, ink);
                    ty += lineStep;
                }
                if (l.showTime()) {
                    int tyy = cy + bubbleH + 2;
                    if (l.incoming()) ui.tiny(l.time(), bx + 3, tyy, ui.fgWellDim());
                    else ui.tiny(l.time(), bx + bubbleW - 3 - ui.tinyWidth(l.time()), tyy, ui.fgWellDim());
                }
            }
            cy += lhgt;
        }
        ui.unclip();

        // Reply
        int sendW = ui.width("send") + 24;
        int iy = y + h - bh;
        ui.textBox(message, x, iy, w - sendW - 4, bh);
        ui.button(x + w - sendW, iy, sendW, bh, "send", !message.text.isBlank() && MinecraftClient.getInstance().getNetworkHandler() != null, this::send);
    }

    private int height(Ui ui, Line l, int lineStep) {
        if (l.separator()) return ui.tinyH() + 10;
        int h = l.text().size() * lineStep + 4 * 2 - 1;
        if (l.showTime()) h += ui.tinyH() + 3;
        return h + (l.runEnds() ? 7 : 2);
    }

    /** Lays the conversation out into bubbles for the width there is, only when something changed. */
    private void layout(Ui ui, String name, List<ChatLog.Message> messages, int width) {
        if (name.equalsIgnoreCase(layoutFor) && messages.size() == layoutCount && width == layoutWidth && ui.scale == layoutScale) return;
        layoutFor = name;
        layoutCount = messages.size();
        layoutWidth = width;
        layoutScale = ui.scale;
        lines.clear();

        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate lastDay = null;
        int bubbleMax = (int) (width * 0.72) - 14;

        List<ChatLog.Message> all = new ArrayList<>(messages);
        for (int i = 0; i < all.size(); i++) {
            ChatLog.Message m = all.get(i);
            ZonedDateTime when = Instant.ofEpochMilli(m.time).atZone(zone);
            LocalDate day = when.toLocalDate();
            if (!day.equals(lastDay)) {
                String label = day.equals(today) ? "today" : day.equals(today.minusDays(1)) ? "yesterday" : DAY.format(when).toLowerCase(java.util.Locale.ROOT);
                lines.add(new Line(true, false, List.of(label), "", 0, false, true));
                lastDay = day;
            }
            List<String> wrapped = ui.wrap(m.text == null ? "" : m.text, bubbleMax);
            int widest = 0;
            for (String line : wrapped) widest = Math.max(widest, ui.width(line));
            // A run ends where the other side speaks next, or after a five minute gap.
            ChatLog.Message next = i + 1 < all.size() ? all.get(i + 1) : null;
            boolean runEnds = next == null || next.incoming != m.incoming || next.time - m.time > 5 * 60_000L
                || !Instant.ofEpochMilli(next.time).atZone(zone).toLocalDate().equals(day);
            lines.add(new Line(false, m.incoming, wrapped, TIME.format(when), widest, runEnds, runEnds));
        }
    }

    private void send() {
        String name = selected();
        String text = message.text.trim();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (name == null || text.isEmpty() || mc.getNetworkHandler() == null) return;

        whisper(name, text);
        message.set("");
        stickToBottom = true;
    }

    private static void whisper(String name, String text) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null) return;
        String command = ControlPanel.whisperCommand().replace("{player}", name).replace("{message}", text);
        if (command.startsWith("/")) command = command.substring(1);
        mc.getNetworkHandler().sendChatCommand(command);
    }

    // Times

    private static long lastSeen(String name) {
        EncounterStore.Encounter e = EncounterStore.find(name);
        return e == null ? 0 : e.lastSeen;
    }

    /** 5s, 12m, 3h, 2d: how long ago, short. */
    static String ago(long time) {
        long s = Math.max(0, (System.currentTimeMillis() - time) / 1000);
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m";
        if (s < 86400) return (s / 3600) + "h";
        if (s < 86400L * 365) return (s / 86400) + "d";
        return (s / (86400L * 365)) + "y";
    }
}
