package dev.jjstools.panel;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * What you have done this session, and in total.
 *
 * A session starts each time you join a server. Totals carry across sessions and restarts, and
 * are written down every minute as well as on leaving, so a crash loses at most a minute.
 * Distance is measured tick to tick; a jump of more than ten blocks in one tick is a teleport or a
 * respawn rather than travel, so it is not counted.
 */
public final class SessionStats {
    public static final class Numbers {
        public long ticks;
        public double walked;
        public double flown;
        public double ridden;
        public long placed;
        public long broken;
        public long deaths;
        public long totems;
        public long pickedUp;
        public long whispersIn;
        public long whispersOut;
        public long chatSent;
        public long playersSeen;
        public long sessions;

        void add(Numbers o, int sign) {
            ticks += sign * o.ticks;
            walked += sign * o.walked;
            flown += sign * o.flown;
            ridden += sign * o.ridden;
            placed += sign * o.placed;
            broken += sign * o.broken;
            deaths += sign * o.deaths;
            totems += sign * o.totems;
            pickedUp += sign * o.pickedUp;
            whispersIn += sign * o.whispersIn;
            whispersOut += sign * o.whispersOut;
            chatSent += sign * o.chatSent;
            playersSeen += sign * o.playersSeen;
            sessions += sign * o.sessions;
        }

        Numbers copy() {
            Numbers n = new Numbers();
            n.add(this, 1);
            return n;
        }
    }

    /** This session so far, for showing. */
    public static Numbers session = new Numbers();
    public static long sessionStart;
    public static String sessionServer = "";

    /** The totals as last written, which already include the session up to the copy below. */
    private static Numbers saved = new Numbers();
    /** How much of this session is already in the saved totals. */
    private static Numbers counted = new Numbers();

    private static boolean loaded;
    private static Path loadedFrom;
    private static boolean inSession;

    private static Vec3d last;
    private static final Set<String> seen = new HashSet<>();
    private static long lastSave;

    private SessionStats() {}

    private static Path path() {
        return PanelFiles.file("stats.json");
    }

    private static void load() {
        Path path = path();
        if (loaded && path.equals(loadedFrom)) return;
        loaded = true;
        loadedFrom = path;
        saved = PanelFiles.read(path, Numbers.class, new Numbers());
    }

    /** Totals across every session, this one included. */
    public static Numbers total() {
        load();
        Numbers t = saved.copy();
        Numbers fresh = session.copy();
        fresh.add(counted, -1);
        t.add(fresh, 1);
        return t;
    }

    public static void onJoin() {
        load();
        if (inSession) save();
        session = new Numbers();
        counted = new Numbers();
        session.sessions = 1;
        sessionStart = System.currentTimeMillis();
        MinecraftClient mc = MinecraftClient.getInstance();
        sessionServer = mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address : "singleplayer";
        seen.clear();
        last = null;
        inSession = true;
    }

    public static void onLeave() {
        if (inSession) save();
        inSession = false;
        last = null;
    }

    /** Writes everything new this session into the totals on disk. */
    public static void save() {
        load();
        Numbers fresh = session.copy();
        fresh.add(counted, -1);
        saved.add(fresh, 1);
        counted = session.copy();
        lastSave = System.currentTimeMillis();
        PanelFiles.write(path(), saved);
    }

    public static void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        if (!inSession) onJoin();

        session.ticks++;

        Vec3d now = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        if (last != null) {
            double d = now.distanceTo(last);
            if (d < 10) {
                if (mc.player.hasVehicle()) session.ridden += d;
                else if (mc.player.isGliding()) session.flown += d;
                else session.walked += d;
            }
        }
        last = now;

        if (System.currentTimeMillis() - lastSave > 60_000) save();
    }

    public static void placed() { session.placed++; }
    public static void broken() { session.broken++; }
    public static void death() { session.deaths++; }
    public static void totem() { session.totems++; }
    public static void pickedUp(int n) { session.pickedUp += Math.max(0, n); }
    public static void chatSent() { session.chatSent++; }

    public static void whisper(boolean incoming) {
        if (incoming) session.whispersIn++;
        else session.whispersOut++;
    }

    public static void sawPlayer(String name) {
        if (seen.add(name.toLowerCase(Locale.ROOT))) session.playersSeen++;
    }
}
