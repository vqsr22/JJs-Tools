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

    private final TextBox search = new TextBox("Search people");
    private final TextBox message = new TextBox("Message");
    private final TextBox newPerson = new TextBox("New chat: name");
    private final TextBox presetLabel = new TextBox("Button name");
    private final TextBox presetText = new TextBox("What it says");

    private final double[] contactScroll = { 0 };
    private final double[] chatScroll = { 0 };
    private final double[] presetScroll = { 0 };
    private boolean stickToBottom = true;

    /** The preset being changed, or -2 for none, -1 for a new one. */
    private int editing = -2;

    /** The conversation laid out into lines, kept until it or the room for it changes. */
    private record Line(boolean separator, boolean incoming, String text, String time, boolean first, boolean last) {}

    private String layoutFor;
    private int layoutCount = -1;
    private int layoutWidth = -1;
    private float layoutScale = -1;
    private final List<Line> lines = new ArrayList<>();

    public ChatPanel() {
        super("chat", "Chat", Items.WRITABLE_BOOK.getDefaultStack(), 300, 160);
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
        int leftW = Math.max(80, Math.min((int) (w * prefs.chatLeft), w - minMid - 60 - gap * 2));
        int rightW = Math.max(50, Math.min((int) (w * prefs.chatRight), w - leftW - minMid - gap * 2));
        int midW = w - leftW - rightW - gap * 2;

        drawContacts(ui, x, y, leftW, h);
        drawConversation(ui, x + leftW + gap, y, midW, h);
        drawPresets(ui, x + leftW + gap + midW + gap, y, rightW, h);

        // The gaps between columns can be dragged to make them wider or narrower.
        int panelX = x;
        int panelW = w;
        ui.dragHandle(x + leftW, y, gap, h, (mx, my) ->
            prefs.chatLeft = Math.max(0.1, Math.min(0.6, (mx - panelX) / (double) panelW)));
        ui.dragHandle(x + leftW + gap + midW, y, gap, h, (mx, my) ->
            prefs.chatRight = Math.max(0.06, Math.min(0.5, (panelX + panelW - mx) / (double) panelW)));

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
        ui.toggle(x, y, half, bh, sort == Sort.Name ? "Name" + nameArrow : "Name", sort == Sort.Name, () -> {
            if (sort == Sort.Name) prefs.chatSortReverse = !prefs.chatSortReverse;
            else {
                prefs.chatSort = "Name";
                prefs.chatSortReverse = false;
            }
        });
        ui.toggle(x + half + 2, y, w - half - 2, bh, sort == Sort.Recent ? "Recent" + arrow : "Recent", sort == Sort.Recent, () -> {
            if (sort == Sort.Recent) prefs.chatSortReverse = !prefs.chatSortReverse;
            else {
                prefs.chatSort = "Recent";
                prefs.chatSortReverse = false;
            }
        });

        int sy = y + bh + 3;
        int hw = ui.width("Hide offline") + 12;
        ui.textBox(search, x, sy, w - hw - 3, bh);
        boolean hideHover = ui.toggle(x + w - hw, sy, hw, bh, "Hide offline", prefs.chatHideOffline, () -> {
            prefs.chatHideOffline = !prefs.chatHideOffline;
            Prefs.save();
        });
        if (hideHover) ui.tooltip("Only list people who are on now. Whoever you have open stays in the list.");
        int ly = sy + bh + 3;
        int listH = h - (ly - y) - bh - 3;

        // Everyone you have talked to, and every friend whether or not you have.
        Map<String, ChatLog.Contact> people = new LinkedHashMap<>();
        for (ChatLog.Contact c : ChatLog.all()) people.put(c.name.toLowerCase(Locale.ROOT), c);
        for (Friend f : Friends.get()) {
            people.computeIfAbsent(f.getName().toLowerCase(Locale.ROOT), k -> {
                ChatLog.Contact c = new ChatLog.Contact();
                c.name = f.getName();
                return c;
            });
        }

        String filter = search.text.trim().toLowerCase(Locale.ROOT);
        List<ChatLog.Contact> friends = new ArrayList<>();
        List<ChatLog.Contact> others = new ArrayList<>();
        for (ChatLog.Contact c : people.values()) {
            if (!filter.isEmpty() && !c.name.toLowerCase(Locale.ROOT).contains(filter)) continue;
            if (prefs.chatHideOffline && !ui.online(c.name) && !c.name.equalsIgnoreCase(selected())) continue;
            (Friends.get().get(c.name) != null ? friends : others).add(c);
        }

        Comparator<ChatLog.Contact> order = sort == Sort.Name
            ? Comparator.comparing(c -> c.name.toLowerCase(Locale.ROOT))
            : Comparator.comparingLong((ChatLog.Contact c) -> c.last).reversed().thenComparing(c -> c.name.toLowerCase(Locale.ROOT));
        if (prefs.chatSortReverse) order = order.reversed();
        // Whoever is on now comes first, then everyone else, each in the chosen order.
        order = Comparator.comparing((ChatLog.Contact c) -> !ui.online(c.name)).thenComparing(order);
        friends.sort(order);
        others.sort(order);

        int row = Math.max(16, ui.lineH() + 8);
        int head = ui.lineH() + 6;
        int contentH = head + Math.max(1, friends.size()) * row + head + Math.max(1, others.size()) * row;

        ui.well(x, ly, w, listH);
        ui.clip(x + 1, ly + 1, w - 2, listH - 2);
        int off = ui.scrollArea(x + 1, ly + 1, w - 2, listH - 2, contentH, contactScroll, row);
        int cy = ly + 1 - off;

        cy = section(ui, x, cy, w, "Friends (" + friends.size() + ")", head);
        if (friends.isEmpty()) {
            ui.text("No friends added", x + 6, cy + (row - ui.lineH()) / 2, ui.fgWellDim());
            cy += row;
        }
        for (ChatLog.Contact c : friends) cy = person(ui, x, cy, w - 7, row, c);

        cy = section(ui, x, cy, w, "Others (" + others.size() + ")", head);
        if (others.isEmpty()) {
            ui.text("Nobody yet", x + 6, cy + (row - ui.lineH()) / 2, ui.fgWellDim());
            cy += row;
        }
        for (ChatLog.Contact c : others) cy = person(ui, x, cy, w - 7, row, c);
        ui.unclip();

        ui.textBox(newPerson, x, y + h - bh, w, bh);
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
        if (c.unread > 0) {
            String n = String.valueOf(c.unread);
            int bw = ui.width(n) + 6;
            ui.fill(right - bw, y + (row - ui.lineH()) / 2 - 1, bw, ui.lineH() + 2, 0xFFE0453A);
            ui.text(n, right - bw + 3, y + (row - ui.lineH()) / 2 + 1, 0xFFFFFFFF);
            right -= bw + 3;
        } else if (c.last > 0) {
            String ago = ago(c.last);
            ui.textRight(ago, right, y + (row - ui.lineH()) / 2 + 1, ui.fgWellDim());
            right -= ui.width(ago) + 4;
        }
        ui.text(ui.fit(c.name, right - nameX), nameX, y + (row - ui.lineH()) / 2 + 1, on ? ui.fgWell() : ui.fgWellDim());

        ui.onClick(x + 1, y, w, row, () -> select(c.name));
        return y + row;
    }

    // Conversation

    private void drawConversation(Ui ui, int x, int y, int w, int h) {
        String name = selected();
        int bh = Math.max(16, ui.lineH() + 7);

        if (name == null) {
            ui.well(x, y, w, h);
            ui.centredWrapped("Pick someone to see your whispers", x, w, y + h / 2, ui.fgWellDim());
            return;
        }

        // Header: who, whether they are on, and quick actions.
        boolean on = ui.online(name);
        int hh = bh + 2;
        ui.head(name, x, y + (hh - 16) / 2, 16);
        ui.text(name, x + 20, y + 2, ui.fg());
        String status;
        if (on) status = "Online";
        else {
            long seen = lastSeen(name);
            status = seen > 0 ? "Offline, last seen " + ago(seen) + " ago" : "Offline";
        }
        int sy = y + 2 + ui.lineH() + 2;
        ui.dot(x + 20, sy + (ui.smallH() - 4) / 2, 4, on ? Ui.GREEN : Ui.GREY_DOT);
        ui.small(status, x + 27, sy, ui.fgDim());

        boolean friend = Friends.get().get(name) != null;
        int fw = ui.width("Unfriend") + 12;
        ui.button(x + w - fw, y, fw, bh, friend ? "Unfriend" : "Add friend", () -> {
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
        int lineStep = lh + 2;
        int contentH = 6;
        for (Line l : lines) contentH += height(l, lineStep);
        contentH += 6;

        // Kept at the newest message unless you have scrolled up to read back.
        int max = Math.max(0, contentH - (mh - 2));
        if (stickToBottom) chatScroll[0] = max;
        ui.clip(x + 1, my + 1, w - 2, mh - 2);
        int off = ui.scrollArea(x + 1, my + 1, w - 2, mh - 2, contentH, chatScroll, lineStep * 3);
        stickToBottom = chatScroll[0] >= max - 1;

        int cy = my + 1 + 6 - off;
        int bubbleMax = (int) (inner * 0.78);
        for (Line l : lines) {
            int lhgt = height(l, lineStep);
            if (cy + lhgt < my || cy > my + mh) {
                cy += lhgt;
                continue;
            }
            if (l.separator()) {
                ui.textCentred(l.text(), x + w / 2, cy + 3, ui.fgWellDim());
            } else {
                int tw = ui.width(l.text());
                int bubbleW = Math.min(bubbleMax, tw) + 10;
                int bx = l.incoming() ? x + 6 : x + w - 8 - bubbleW;
                int top = l.first() ? 3 : 0;
                int bottom = l.last() ? lh + 3 : 0;
                int colour = l.incoming() ? (ui.dark ? 0xFF2E3036 : 0xFFF2F2F2) : (ui.dark ? 0xFF2F4C86 : 0xFFCFE0FF);
                int textColour = l.incoming() ? (ui.dark ? 0xFFECECEC : 0xFF202020) : (ui.dark ? 0xFFFFFFFF : 0xFF102040);
                ui.fill(bx, cy, bubbleW, lineStep + top + bottom, colour);
                ui.text(l.text(), bx + 5, cy + top + 1, textColour);
                if (l.last()) {
                    int timeColour = l.incoming() ? (ui.dark ? 0xFF9A9A9A : 0xFF707070) : (ui.dark ? 0xFFB8C8F0 : 0xFF4A5A80);
                    if (l.incoming()) ui.text(l.time(), bx + 5, cy + top + lineStep, timeColour);
                    else ui.textRight(l.time(), bx + bubbleW - 5, cy + top + lineStep, timeColour);
                }
            }
            cy += lhgt;
        }
        ui.unclip();

        // Reply
        int sendW = ui.width("Send") + 16;
        int iy = y + h - bh;
        ui.textBox(message, x, iy, w - sendW - 4, bh);
        ui.button(x + w - sendW, iy, sendW, bh, "Send", !message.text.isBlank() && MinecraftClient.getInstance().getNetworkHandler() != null, this::send);
    }

    private int height(Line l, int lineStep) {
        if (l.separator()) return lineStep + 8;
        int extra = (l.first() ? 3 : 0) + (l.last() ? lineStep + 3 + 3 : 0);
        return lineStep + extra;
    }

    /** Lays the conversation out into lines for the width there is, only when something changed. */
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
        int bubbleMax = (int) (width * 0.78);

        for (ChatLog.Message m : new ArrayList<>(messages)) {
            ZonedDateTime when = Instant.ofEpochMilli(m.time).atZone(zone);
            LocalDate day = when.toLocalDate();
            if (!day.equals(lastDay)) {
                String label = day.equals(today) ? "Today" : day.equals(today.minusDays(1)) ? "Yesterday" : DAY.format(when);
                lines.add(new Line(true, false, label, "", false, false));
                lastDay = day;
            }
            List<String> wrapped = ui.wrap(m.text == null ? "" : m.text, bubbleMax);
            for (int i = 0; i < wrapped.size(); i++) {
                lines.add(new Line(false, m.incoming, wrapped.get(i), TIME.format(when), i == 0, i == wrapped.size() - 1));
            }
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

    // Quick messages

    private void drawPresets(Ui ui, int x, int y, int w, int h) {
        Prefs.Data prefs = Prefs.get();
        int bh = ui.lineH() + 5;
        String to = selected();

        ui.text(ui.fit("Quick", w), x, y + 2, ui.fgDim());
        int ly = y + ui.lineH() + 6;

        if (editing != -2) {
            int ey = ly;
            ui.textBox(presetLabel, x, ey, w, bh + 2);
            ey += bh + 4;
            ui.textBox(presetText, x, ey, w, bh + 2);
            ey += bh + 4;
            ui.button(x, ey, w, bh, "Save", !presetLabel.text.isBlank() && !presetText.text.isBlank(), () -> {
                Prefs.Preset p = new Prefs.Preset(presetLabel.text.trim(), presetText.text.trim());
                if (editing >= 0 && editing < prefs.presets.size()) prefs.presets.set(editing, p);
                else prefs.presets.add(p);
                editing = -2;
                Prefs.save();
            });
            ey += bh + 2;
            ui.button(x, ey, w, bh, "Cancel", () -> editing = -2);
            ey += bh + 2;
            if (editing >= 0) {
                ui.button(x, ey, w, bh, "Delete", () -> {
                    if (editing >= 0 && editing < prefs.presets.size()) prefs.presets.remove(editing);
                    editing = -2;
                    Prefs.save();
                });
            }
            if (ui.hovered(x, ly, w, bh * 2 + 6)) ui.tooltip("{player} is who it goes to. Start with / to run it as a command.");
            return;
        }

        int step = bh + 2;
        int listH = h - (ly - y) - bh - 2;
        int contentH = prefs.presets.size() * step;
        ui.clip(x, ly, w, listH);
        int off = ui.scrollArea(x, ly, w, listH, contentH, presetScroll, step);
        int py = ly - off;
        for (int i = 0; i < prefs.presets.size(); i++) {
            Prefs.Preset p = prefs.presets.get(i);
            int index = i;
            boolean hover = ui.button(x, py, w - (contentH > listH ? 4 : 0), bh, p.label, to != null || p.message.startsWith("/"), () -> runPreset(p, to));
            if (hover || ui.hovered(x, py, w, bh)) ui.tooltip((to == null ? "" : "To " + to + ": ") + p.message + "  (right-click to change)");
            ui.onClick(x, py, w, bh, 1, b -> {
                editing = index;
                presetLabel.set(p.label);
                presetText.set(p.message);
            });
            py += step;
        }
        ui.unclip();

        ui.button(x, y + h - bh, w, bh, "+", () -> {
            editing = -1;
            presetLabel.set("");
            presetText.set("");
        });
    }

    private void runPreset(Prefs.Preset p, String to) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null) return;
        String text = to == null ? p.message : p.message.replace("{player}", to);
        if (text.startsWith("/")) mc.getNetworkHandler().sendChatCommand(text.substring(1));
        else if (to != null) whisper(to, text);
        stickToBottom = true;
    }

    // Times

    private static long lastSeen(String name) {
        for (EncounterStore.Encounter e : EncounterStore.all()) {
            if (e.name != null && e.name.equalsIgnoreCase(name)) return e.lastSeen;
        }
        return 0;
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
