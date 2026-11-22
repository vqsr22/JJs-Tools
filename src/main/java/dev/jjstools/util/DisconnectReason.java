package dev.jjstools.util;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.network.packet.Packet;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Says why you were disconnected, under the game's own message.
 *
 * The game often shows only "lost connection", which can mean the server kicked you, the
 * connection was dropped with no message (by the server, a proxy in front of it or the network),
 * an error in the connection on your side, or a mod disconnecting you on purpose. Which of those
 * it was is noted as it happens (ClientConnectionDisconnectMixin), with how long you had been in
 * the world and the last packets each way, and added to the disconnect screen
 * (DisconnectedScreenMixin) and the log. The switch is in Meteor's Config tab, on by default.
 */
public final class DisconnectReason {
    private DisconnectReason() {}

    private static Setting<Boolean> enabled;

    private static final int KEEP = 6;
    private static final ArrayDeque<String> received = new ArrayDeque<>();
    private static final ArrayDeque<String> sent = new ArrayDeque<>();

    private static volatile long joinedAt;
    private static volatile long kickedAt;
    private static volatile Throwable error;
    private static volatile long errorAt;
    private static volatile long closedAt;
    private static volatile String byMod;
    private static volatile long byModAt;

    public static void init() {
        enabled = Config.get().settings.createGroup("JJ's Tools").add(new BoolSetting.Builder()
            .name("disconnect-reason")
            .description("Says on the disconnect screen why you were disconnected: kicked by the server, the connection dropped with no message, an error in the connection, or a mod disconnecting you, with how long you were in and the last packets each way.")
            .defaultValue(true)
            .build());
        MeteorClient.EVENT_BUS.subscribe(DisconnectReason.class);
    }

    public static boolean on() {
        return enabled == null || enabled.get();
    }

    // Noting what happens

    @EventHandler
    private static void onJoin(GameJoinedEvent event) {
        joinedAt = System.currentTimeMillis();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private static void onReceive(PacketEvent.Receive event) {
        Packet<?> p = event.packet;
        if (p instanceof net.minecraft.network.packet.s2c.common.DisconnectS2CPacket
            || p instanceof net.minecraft.network.packet.s2c.login.LoginDisconnectS2CPacket) {
            kickedAt = System.currentTimeMillis();
        }
        note(received, p);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private static void onSent(PacketEvent.Sent event) {
        note(sent, event.packet);
        String who = senderMod();
        long now = System.currentTimeMillis();
        synchronized (DisconnectReason.class) {
            senders.addLast(new Sender(now, who));
            while (!senders.isEmpty() && (senders.size() > 4000 || now - senders.peekFirst().at > WINDOW_MS)) senders.pollFirst();
        }
    }

    // Who sends what

    private static final long WINDOW_MS = 5000;
    private record Sender(long at, String mod) {}
    private static final ArrayDeque<Sender> senders = new ArrayDeque<>();
    private static final String GAME_NAME = "the game itself";

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    /** The mod whose code sent the packet being sent now, from who called. */
    private static String senderMod() {
        try {
            return WALKER.walk(frames -> frames.limit(64)
                .map(f -> modOf(f.getDeclaringClass(), f.getMethodName()))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(GAME_NAME));
        } catch (Throwable t) {
            return GAME_NAME;
        }
    }

    private static final java.util.Map<Class<?>, String> CLASS_MOD = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile java.util.Map<String, String> jarMods;

    /**
     * The mod a frame belongs to, or null when it is the game, the loader or plumbing that only
     * passes things on (Meteor's events and helpers, this class).
     */
    private static String modOf(Class<?> cls, String method) {
        // A mod's mixin inside a game class: handler$abc000$modid$name.
        if (method.startsWith("handler$") || method.startsWith("redirect$") || method.startsWith("wrapOperation$")
            || method.startsWith("modify$") || method.startsWith("localvar$")) {
            // Hooks in the sending itself (ViaFabricPlus, packet fixers) are on every packet's
            // way out; whoever asked for the packet is further up.
            if (net.minecraft.network.ClientConnection.class.isAssignableFrom(cls)
                || net.minecraft.client.network.ClientCommonNetworkHandler.class.isAssignableFrom(cls)) return null;
            String[] parts = method.split("\\$");
            if (parts.length >= 3 && !parts[2].equals("meteor-client")) return modName(parts[2]);
            return null;
        }
        String name = cls.getName();
        for (String g : GAME) if (name.startsWith(g)) return null;
        if (name.startsWith("meteordevelopment.orbit.") || name.startsWith("meteordevelopment.meteorclient.mixin.")
            || name.startsWith("meteordevelopment.meteorclient.utils.") || name.startsWith("meteordevelopment.meteorclient.events.")
            || name.startsWith("dev.jjstools.util.PacketLimits") || name.startsWith("dev.jjstools.modules.NoPacketKicks")) return null;
        String mod = CLASS_MOD.computeIfAbsent(cls, DisconnectReason::jarMod);
        return mod.isEmpty() ? null : mod;
    }

    /** The mod whose jar a class was loaded from, by name; empty if it is not in a mod's jar. */
    private static String jarMod(Class<?> cls) {
        try {
            var source = cls.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return cls.getName();
            String where = java.nio.file.Paths.get(source.getLocation().toURI()).toAbsolutePath().normalize().toString();
            java.util.Map<String, String> mods = jarMods;
            if (mods == null) {
                mods = new java.util.HashMap<>();
                for (var mod : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) {
                    for (var path : mod.getOrigin().getPaths()) {
                        mods.put(path.toAbsolutePath().normalize().toString(), mod.getMetadata().getName());
                    }
                }
                jarMods = mods;
            }
            String mod = mods.get(where);
            if (mod != null) return mod;
            // Dev runs and nested jars: the nearest mod path above it.
            for (var e : mods.entrySet()) if (where.startsWith(e.getKey())) return e.getValue();
            return cls.getName();
        } catch (Throwable t) {
            return cls.getName();
        }
    }

    private static String modName(String id) {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(id)
            .map(m -> m.getMetadata().getName()).orElse(id);
    }

    /** Packets sent in the last few seconds, by mod, most first. */
    private static List<java.util.Map.Entry<String, Integer>> recentSenders(long now) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        synchronized (DisconnectReason.class) {
            for (Sender s : senders) if (now - s.at() <= WINDOW_MS) counts.merge(s.mod(), 1, Integer::sum);
        }
        List<java.util.Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        return list;
    }

    /** The mod an error came from: the first mod frame in it or what it was caused by. */
    private static String errorMod(Throwable t) {
        for (Throwable e = t; e != null; e = e.getCause() == e ? null : e.getCause()) {
            for (StackTraceElement f : e.getStackTrace()) {
                String m = callerMod(new StackTraceElement[] { f });
                if (m != null) return m;
            }
        }
        return null;
    }

    private static void note(ArrayDeque<String> list, Packet<?> p) {
        String name;
        try {
            name = p.getPacketType().id().getPath();
        } catch (Throwable t) {
            name = p.getClass().getSimpleName();
        }
        synchronized (DisconnectReason.class) {
            list.addLast(name);
            while (list.size() > KEEP) list.pollFirst();
        }
    }

    /** An error in the connection (ClientConnection.exceptionCaught). */
    public static void onError(Throwable t) {
        if (error == null || System.currentTimeMillis() - errorAt > 5000) {
            error = t;
            errorAt = System.currentTimeMillis();
        }
    }

    /** The connection closed (ClientConnection.channelInactive). */
    public static void onClosed() {
        closedAt = System.currentTimeMillis();
    }

    /**
     * Disconnect was called (ClientConnection.disconnect). If a mod called it, rather than the
     * game reacting to the connection, the mod is named from who called.
     */
    public static void onDisconnect() {
        long now = System.currentTimeMillis();
        // The game passing on a kick or a dropped connection, not anyone deciding to leave.
        if (now - kickedAt < 1500 || now - closedAt < 1500 || now - errorAt < 1500) return;
        String mod = callerMod(new Throwable().getStackTrace());
        if (mod != null) {
            byMod = mod;
            byModAt = System.currentTimeMillis();
        }
    }

    private static final String[] GAME = {
        "java.", "jdk.", "sun.", "io.netty.", "com.mojang.", "net.minecraft.", "net.fabricmc.",
        "org.spongepowered.", "com.llamalad7.", "it.unimi.", "org.lwjgl.", "dev.jjstools.util.DisconnectReason",
        "dev.jjstools.mixin.ClientConnectionDisconnectMixin", "org.prismlauncher.", "knot"
    };

    /** The first caller that is not the game itself, as a mod or a class, or null. */
    private static String callerMod(StackTraceElement[] trace) {
        for (StackTraceElement e : trace) {
            String cls = e.getClassName();
            String method = e.getMethodName();
            // A mod's mixin inside a game class: handler$abc000$modid$name.
            if (method.startsWith("handler$") || method.startsWith("redirect$") || method.startsWith("wrapOperation$")) {
                String[] parts = method.split("\\$");
                if (parts.length >= 4 && !parts[2].equals("jjs-tools") && !parts[2].equals("meteor-client")) return modName(parts[2]);
                if (parts.length >= 4 && parts[2].equals("meteor-client")) continue;
            }
            boolean game = false;
            for (String g : GAME) {
                if (cls.startsWith(g)) {
                    game = true;
                    break;
                }
            }
            if (game) continue;
            // Meteor's own plumbing calls through for others; its modules count.
            if (cls.startsWith("meteordevelopment.orbit.") || cls.startsWith("meteordevelopment.meteorclient.mixin.")
                || cls.startsWith("meteordevelopment.meteorclient.utils.") || cls.startsWith("meteordevelopment.meteorclient.events.")) continue;
            try {
                String mod = CLASS_MOD.computeIfAbsent(Class.forName(cls, false, DisconnectReason.class.getClassLoader()), DisconnectReason::jarMod);
                if (!mod.isEmpty() && !mod.equals(cls)) return mod;
            } catch (Throwable ignored) {
            }
            return cls + "." + method;
        }
        return null;
    }

    // Telling

    /** The game's reason with the cause added under it, or the game's own if there is nothing to add. */
    public static Text extend(Text reason) {
        if (!on()) return reason;
        long now = System.currentTimeMillis();
        List<Text> lines = new ArrayList<>();

        String cause;
        boolean recent = now - Math.max(Math.max(kickedAt, closedAt), Math.max(errorAt, byModAt)) < 15_000;
        if (!recent) return reason;
        if (now - kickedAt < 15_000) {
            cause = "The server kicked you with the message above.";
        } else if (error != null && now - errorAt < 15_000) {
            Throwable root = error;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            String msg = root.getMessage();
            cause = "Connection error on your side: " + root.getClass().getSimpleName() + (msg != null ? ": " + shorten(msg, 160) : "") + ".";
            String from = errorMod(error);
            if (from != null) cause += " It came from " + from + ".";
        } else if (now - byModAt < 15_000 && byMod != null) {
            cause = "Disconnected on purpose by " + byMod + ".";
        } else {
            cause = "The connection was closed with no message: by the server, a proxy in front of it, or the network. Nothing on your side ended it.";
        }
        lines.add(Text.literal(cause).formatted(Formatting.YELLOW));

        // Who was sending: servers kick (or drop) for too many packets, or the wrong ones, so
        // the mod sending the most is the likely one.
        var bySender = recentSenders(now);
        int total = 0;
        for (var e : bySender) total += e.getValue();
        if (total > 0) {
            String likely = null;
            for (var e : bySender) {
                if (!e.getKey().equals(GAME_NAME)) {
                    likely = e.getKey();
                    break;
                }
            }
            if (likely != null && (now - byModAt >= 15_000 || byMod == null)) {
                int n = 0;
                for (var e : bySender) if (e.getKey().equals(likely)) n = e.getValue();
                lines.add(Text.literal("Likely from: " + likely + " (sent " + n + " of your last " + total + " packets, in " + WINDOW_MS / 1000 + " s).").formatted(Formatting.GOLD));
            }
            StringBuilder who = new StringBuilder("Packets sent in the last " + WINDOW_MS / 1000 + " s: ");
            int shown = 0;
            for (var e : bySender) {
                if (shown++ == 5) break;
                if (shown > 1) who.append(", ");
                who.append(e.getKey()).append(' ').append(e.getValue());
            }
            lines.add(Text.literal(who.toString()).formatted(Formatting.GRAY));
        }

        if (joinedAt > 0) lines.add(Text.literal(String.format("In the world for %.1f s.", (now - joinedAt) / 1000.0)).formatted(Formatting.GRAY));
        String in, out;
        synchronized (DisconnectReason.class) {
            in = String.join(", ", received);
            out = String.join(", ", sent);
        }
        if (!in.isEmpty()) lines.add(Text.literal("Last received: " + in).formatted(Formatting.DARK_GRAY));
        if (!out.isEmpty()) lines.add(Text.literal("Last sent: " + out).formatted(Formatting.DARK_GRAY));

        StringBuilder log = new StringBuilder("Disconnected: ").append(reason.getString());
        for (Text t : lines) log.append(" | ").append(t.getString());
        if (error != null && now - errorAt < 15_000) JJsTools.LOG.warn(log.toString(), error);
        else JJsTools.LOG.warn(log.toString());

        reset();
        MutableText out2 = reason.copy();
        out2.append(Text.literal("\n"));
        for (Text t : lines) out2.append(Text.literal("\n")).append(t);
        return out2;
    }

    private static void reset() {
        kickedAt = closedAt = errorAt = byModAt = 0;
        error = null;
        byMod = null;
        synchronized (DisconnectReason.class) {
            received.clear();
            sent.clear();
            senders.clear();
        }
    }

    private static String shorten(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
