package dev.jjstools.panel;

import com.google.gson.reflect.TypeToken;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who you have blocked on 2b2t.
 *
 * 2b2t blocks with /ignore, and lists with /ignorelist, a page at a time under a heading like
 * "[ Ignored players [<] 1/8 [>] ]", one "Name [hard]" a line. Refreshing from the panel asks for
 * the first page, reads it, and asks for each next page in turn until the last, keeping those
 * lines out of your chat. Blocking or unblocking from anywhere is caught from the server's reply
 * too. The copy is saved per server, so
 * it shows even before you have asked again.
 */
public final class BlockedList {
    public static final class Server {
        public String address;
        public long refreshed;
        public List<String> names = new ArrayList<>();
        /** What kind of block each name has, such as "hard", by lower case name. */
        public Map<String, String> kinds = new HashMap<>();
    }

    private static final Pattern HIDDEN = Pattern.compile("^Chat messages from ([A-Za-z0-9_]{1,16}) will be hidden\\.?$");
    private static final Pattern SHOWN = Pattern.compile("^Chat messages from ([A-Za-z0-9_]{1,16}) will be shown\\.?$");
    private static final Pattern COUNT = Pattern.compile("^You have (\\d+) players? ignored\\.?$");
    /** A page heading, such as "[ Ignored players [<] 1/8 [>] ]". */
    private static final Pattern PAGE_NUMBERS = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");
    /** A name on its own, or followed by the kind of block, such as "Name [hard]". */
    private static final Pattern ENTRY = Pattern.compile("^[^A-Za-z0-9_]*([A-Za-z0-9_]{1,16})(?:\\s*[\\[(]([^\\])]{1,12})[\\])])?\\s*$");

    private static final TreeMap<String, Server> SERVERS = new TreeMap<>();
    private static boolean loaded;
    private static Path loadedFrom;

    /** Reading the lines of an /ignorelist reply, and what has been read so far. */
    private static boolean reading;
    private static long readingSince;
    private static final List<String> incoming = new ArrayList<>();
    private static final Map<String, String> incomingKinds = new HashMap<>();

    /** A refresh from the panel, going through every page of the list by itself. */
    private static boolean auto;
    private static long autoStarted;
    private static int page, pages;
    private static String nextCommand;
    private static long nextAt;
    private static long finishAt;

    private BlockedList() {}

    private static Path path() {
        return PanelFiles.file("blocked.json");
    }

    private static void load() {
        Path path = path();
        if (loaded && path.equals(loadedFrom)) return;
        loaded = true;
        loadedFrom = path;
        SERVERS.clear();
        List<Server> list = PanelFiles.read(path, new TypeToken<List<Server>>() {}.getType(), new ArrayList<Server>());
        for (Server s : list) {
            if (s == null || s.address == null) continue;
            if (s.names == null) s.names = new ArrayList<>();
            if (s.kinds == null) s.kinds = new HashMap<>();
            SERVERS.put(s.address, s);
        }
    }

    private static void save() {
        PanelFiles.write(path(), new ArrayList<>(SERVERS.values()));
    }

    private static String address() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address.toLowerCase(Locale.ROOT) : "singleplayer";
    }

    /** The list for the server you are on. */
    public static synchronized Server current() {
        load();
        return SERVERS.computeIfAbsent(address(), a -> {
            Server s = new Server();
            s.address = a;
            return s;
        });
    }

    /** Whether a refresh is going through the pages right now. */
    public static synchronized boolean refreshing() {
        return auto;
    }

    /** Which page a refresh is on, for showing progress. */
    public static synchronized String progress() {
        return auto && pages > 0 ? page + "/" + pages : null;
    }

    /**
     * Asks the server for the whole list. 2b2t shows it a page at a time, so each page is asked
     * for in turn, a moment apart, and its lines are kept out of your chat while that happens.
     */
    public static synchronized void refresh() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null) return;
        auto = true;
        autoStarted = System.currentTimeMillis();
        page = 0;
        pages = 0;
        nextCommand = null;
        nextAt = 0;
        finishAt = 0;
        incoming.clear();
        incomingKinds.clear();
        reading = true;
        readingSince = System.currentTimeMillis();
        mc.getNetworkHandler().sendChatCommand("ignorelist");
    }

    /** Blocks or unblocks: /ignore is a toggle, and the reply says which it did. */
    public static void toggle(String name) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null || name == null || name.isBlank()) return;
        mc.getNetworkHandler().sendChatCommand("ignore " + name.trim());
    }

    public static synchronized boolean isBlocked(String name) {
        for (String n : current().names) if (n.equalsIgnoreCase(name)) return true;
        return false;
    }

    /** Looks at a chat line for one of the replies. Returns true if it should be kept out of chat. */
    public static synchronized boolean onChat(Text text) {
        if (text == null) return false;
        // A page often arrives as one message with a line for each name, so each line is read on its own.
        boolean hide = false;
        for (String part : text.getString().split("\\R")) {
            if (line(ChatText.clean(part), text)) hide = true;
        }
        return hide;
    }

    private static boolean line(String plain, Text text) {
        if (plain.isEmpty()) return auto && reading;
        String lower = plain.toLowerCase(Locale.ROOT);

        // A page heading: which page of how many, and the command its next arrow runs.
        String head = lower.replaceFirst("^[^a-z]*", "");
        if (head.startsWith("ignored players") || head.startsWith("ignore list") && !head.contains("deleted") && !head.contains("cleared")) {
            Matcher numbers = PAGE_NUMBERS.matcher(plain);
            int p = 1;
            int of = 1;
            if (numbers.find()) {
                p = Integer.parseInt(numbers.group(1));
                of = Math.max(p, Integer.parseInt(numbers.group(2)));
            }
            dev.jjstools.JJsTools.LOG.info("[Control Panel] Blocked list page {} of {}: {}", p, of, plain);
            if (p == 1 || !reading) {
                incoming.clear();
                incomingKinds.clear();
            }
            reading = true;
            readingSince = System.currentTimeMillis();
            page = p;
            pages = of;
            nextCommand = nextPageCommand(text);
            if (auto) {
                if (p < of) nextAt = System.currentTimeMillis() + 700;
                else finishAt = System.currentTimeMillis() + 700;
            }
            return auto;
        }

        // The old one-page form.
        if (lower.startsWith("ignored players") || lower.startsWith("ignore list") || lower.startsWith("ignored:")) {
            reading = true;
            readingSince = System.currentTimeMillis();
            incoming.clear();
            incomingKinds.clear();
            int colon = plain.indexOf(':');
            if (colon >= 0) names(plain.substring(colon + 1));
            if (auto) finishAt = System.currentTimeMillis() + 1500;
            return auto;
        }

        if (reading) {
            if (COUNT.matcher(plain).matches()) {
                finish(true);
                return auto;
            }
            Matcher entry = ENTRY.matcher(plain);
            if (entry.matches()) {
                incoming.add(entry.group(1));
                if (entry.group(2) != null) incomingKinds.put(entry.group(1).toLowerCase(Locale.ROOT), entry.group(2).toLowerCase(Locale.ROOT));
                readingSince = System.currentTimeMillis();
                return auto;
            }
            if (names(plain)) return auto;
        }

        Matcher hidden = HIDDEN.matcher(plain);
        if (hidden.matches()) {
            Server s = current();
            if (!isBlocked(hidden.group(1))) {
                s.names.add(hidden.group(1));
                s.names.sort(String.CASE_INSENSITIVE_ORDER);
                save();
            }
            return false;
        }

        Matcher shown = SHOWN.matcher(plain);
        if (shown.matches()) {
            current().names.removeIf(n -> n.equalsIgnoreCase(shown.group(1)));
            save();
            return false;
        }

        if (lower.startsWith("ignore list deleted") || lower.startsWith("ignore list cleared")) {
            current().names.clear();
            save();
        }
        return false;
    }

    /** The command behind the heading's next page arrow, if it has one, read off the text itself. */
    private static String nextPageCommand(Text text) {
        List<Text> parts = new ArrayList<>();
        collect(text, parts);
        String found = null;
        for (Text part : parts) {
            ClickEvent click = part.getStyle().getClickEvent();
            if (click == null || !"RUN_COMMAND".equalsIgnoreCase(click.getAction().name())) continue;
            String command = commandOf(click);
            if (command == null) continue;
            // The next arrow comes after the previous one, so the last command found is the one wanted.
            if (part.getString().contains(">") || found == null) found = command;
        }
        return found;
    }

    private static void collect(Text text, List<Text> into) {
        into.add(text);
        for (Text sibling : text.getSiblings()) collect(sibling, into);
    }

    /** The command a run-command click carries, found without relying on what its method is called. */
    private static String commandOf(ClickEvent click) {
        for (java.lang.reflect.Method m : click.getClass().getMethods()) {
            if (m.getParameterCount() != 0 || m.getReturnType() != String.class) continue;
            if (m.getName().equals("toString")) continue;
            try {
                Object value = m.invoke(click);
                if (value instanceof String s && s.startsWith("/")) return s;
            } catch (Exception ignored) {
                // Not that one.
            }
        }
        return null;
    }

    /** Takes names from a line of the list: one name, or several split by commas or spaces. Returns whether it was one. */
    private static boolean names(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return false;
        String[] parts = trimmed.split("[,\\s]+");
        for (String part : parts) if (!NAME_LINE.matcher(part).matches()) return false;
        for (String part : parts) incoming.add(part);
        readingSince = System.currentTimeMillis();
        return true;
    }

    private static final Pattern NAME_LINE = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    /**
     * What has been read becomes the list for this server. A full read replaces it; pages you
     * flicked through yourself are added to it, since the rest of the list was not seen.
     */
    private static void finish(boolean whole) {
        Server s = current();
        if (whole) {
            s.names = new ArrayList<>(incoming);
            s.kinds = new HashMap<>(incomingKinds);
        } else {
            for (String n : incoming) if (!isBlocked(n)) s.names.add(n);
            s.kinds.putAll(incomingKinds);
        }
        s.names.sort(String.CASE_INSENSITIVE_ORDER);
        s.refreshed = System.currentTimeMillis();
        reading = false;
        auto = false;
        nextAt = 0;
        finishAt = 0;
        save();
    }

    public static synchronized void tick() {
        long now = System.currentTimeMillis();
        MinecraftClient mc = MinecraftClient.getInstance();

        if (auto && nextAt > 0 && now >= nextAt) {
            nextAt = 0;
            if (mc.getNetworkHandler() != null) {
                String command = nextCommand != null ? nextCommand : "/ignorelist " + (page + 1);
                mc.getNetworkHandler().sendChatCommand(command.startsWith("/") ? command.substring(1) : command);
            }
        }
        if (auto && finishAt > 0 && now >= finishAt) finish(true);

        // Given up on if pages stop coming.
        if (auto && now - autoStarted > 30_000) finish(page >= pages && pages > 0);
        if (!auto && reading && now - readingSince > 2500) finish(pages <= 1);
    }
}
