package dev.jjstools.panel;

import com.google.gson.reflect.TypeToken;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every whisper you have sent or been sent, kept per person like a text messenger.
 *
 * Whispers are read off chat as they arrive. 2b2t's chat plugin shows them as "Name whispers:
 * hello" coming in and "To Name: hello" going out; vanilla's "whispers to you" and "You whisper
 * to" and Essentials' "[Name -> me]" are understood too. Both directions are taken from what the
 * server shows, so a whisper sent from the normal chat box is logged the same as one sent from
 * the panel, and only once.
 *
 * Saved to this account's folder, a little after each change.
 */
public final class ChatLog {
    public static final class Message {
        public long time;
        public boolean incoming;
        public String text;

        public Message() {}

        Message(long time, boolean incoming, String text) {
            this.time = time;
            this.incoming = incoming;
            this.text = text;
        }
    }

    public static final class Contact {
        public String name;
        public long last;
        public int unread;
        public List<Message> messages = new ArrayList<>();
    }

    private static final int MAX_PER_CONTACT = 5000;
    private static final String NAME = "([A-Za-z0-9_]{1,16})";

    private static final Pattern[] INCOMING = {
        Pattern.compile("^" + NAME + " whispers(?: to you)?: (.*)$"),
        Pattern.compile("^\\[" + NAME + " -> (?:me|you)\\] (.*)$"),
        Pattern.compile("^From " + NAME + ": (.*)$"),
    };
    private static final Pattern[] OUTGOING = {
        Pattern.compile("^[Tt]o " + NAME + ": (.*)$"),
        Pattern.compile("^You whisper to " + NAME + ": (.*)$"),
        Pattern.compile("^\\[(?:me|you) -> " + NAME + "\\] (.*)$"),
    };

    private static final Map<String, Contact> CONTACTS = new LinkedHashMap<>();
    private static boolean loaded;
    private static Path loadedFrom;
    private static boolean dirty;
    private static long changedAt;

    /** Whose conversation the panel has open, so whispers from them do not count as unread. */
    public static String open;

    private ChatLog() {}

    private static Path path() {
        return PanelFiles.file("chats.json");
    }

    private static void load() {
        Path path = path();
        if (loaded && path.equals(loadedFrom)) return;
        loaded = true;
        loadedFrom = path;
        CONTACTS.clear();

        List<Contact> list = PanelFiles.read(path, new TypeToken<List<Contact>>() {}.getType(), new ArrayList<>());
        for (Contact c : list) {
            if (c != null && c.name != null) CONTACTS.put(c.name.toLowerCase(Locale.ROOT), c);
        }
    }

    /** Looks at a chat line for a whisper. */
    public static void onChat(String message) {
        if (message == null || message.isEmpty()) return;
        for (String line : message.split("\\R")) one(ChatText.clean(line));
    }

    private static void one(String plain) {
        if (plain.isEmpty()) return;

        for (Pattern p : INCOMING) {
            Matcher m = p.matcher(plain);
            if (m.matches()) {
                add(m.group(1), true, m.group(2));
                return;
            }
        }
        for (Pattern p : OUTGOING) {
            Matcher m = p.matcher(plain);
            if (m.matches()) {
                add(m.group(1), false, m.group(2));
                return;
            }
        }
    }

    private static synchronized void add(String name, boolean incoming, String text) {
        load();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && name.equalsIgnoreCase(mc.player.getGameProfile().name())) return;

        Contact c = contact(name);
        long now = System.currentTimeMillis();
        c.messages.add(new Message(now, incoming, text));
        if (c.messages.size() > MAX_PER_CONTACT) c.messages.subList(0, c.messages.size() - MAX_PER_CONTACT).clear();
        c.last = now;
        if (incoming && !name.equalsIgnoreCase(open)) c.unread++;

        SessionStats.whisper(incoming);
        dirty = true;
        changedAt = now;
    }

    /** The conversation with someone, made if there is none yet. */
    public static synchronized Contact contact(String name) {
        load();
        return CONTACTS.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> {
            Contact c = new Contact();
            c.name = name;
            return c;
        });
    }

    public static synchronized Contact find(String name) {
        load();
        return name == null ? null : CONTACTS.get(name.toLowerCase(Locale.ROOT));
    }

    public static synchronized Collection<Contact> all() {
        load();
        return new ArrayList<>(CONTACTS.values());
    }

    public static synchronized void markRead(String name) {
        Contact c = find(name);
        if (c != null && c.unread > 0) {
            c.unread = 0;
            dirty = true;
            changedAt = System.currentTimeMillis();
        }
    }

    public static synchronized void forget(String name) {
        load();
        if (CONTACTS.remove(name.toLowerCase(Locale.ROOT)) != null) {
            dirty = true;
            changedAt = 0;
        }
    }

    /** Forgets every whisper with everyone, and saves straight away. */
    public static synchronized void clearAll() {
        load();
        CONTACTS.clear();
        save();
    }

    public static synchronized int unreadTotal() {
        load();
        int n = 0;
        for (Contact c : CONTACTS.values()) n += c.unread;
        return n;
    }

    /** Saves a couple of seconds after the last change, so a burst of whispers is one write. */
    public static synchronized void tick() {
        if (dirty && System.currentTimeMillis() - changedAt > 2000) save();
    }

    public static synchronized void save() {
        if (!loaded) return;
        dirty = false;
        PanelFiles.write(path(), new ArrayList<>(CONTACTS.values()));
    }
}
