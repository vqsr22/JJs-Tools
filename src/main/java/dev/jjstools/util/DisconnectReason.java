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
                if (parts.length >= 4 && !parts[2].equals("jjs-tools") && !parts[2].equals("meteor-client")) return parts[2] + " (" + parts[3] + ")";
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
        } else if (now - byModAt < 15_000 && byMod != null) {
            cause = "Disconnected on purpose by " + byMod + ".";
        } else {
            cause = "The connection was closed with no message: by the server, a proxy in front of it, or the network. Nothing on your side ended it.";
        }
        lines.add(Text.literal(cause).formatted(Formatting.YELLOW));

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
        }
    }

    private static String shorten(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
