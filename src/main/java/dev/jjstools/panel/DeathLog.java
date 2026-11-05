package dev.jjstools.panel;

import com.google.gson.reflect.TypeToken;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import net.minecraft.text.TextContent;
import net.minecraft.text.TranslatableTextContent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everywhere you have died: when, where, and what did it.
 *
 * The server sends your death message as you die, and in vanilla it is a translation with a key
 * that says exactly what happened, "death.attack.player" or "death.attack.inWall", and the
 * killer's name among its parts. That key is sorted into a plain cause. Servers that send the
 * message as ordinary text are read by its wording instead, which covers the usual messages.
 */
public final class DeathLog {
    public enum Cause {
        Player("Player"), Mob("Mob"), Fall("Fall"), Fire("Fire"), Lava("Lava"), Suffocation("Suffocation"),
        Drowning("Drowning"), Explosion("Explosion"), Void("Void"), Starvation("Starvation"), Wither("Wither"),
        Magic("Magic"), Freezing("Freezing"), Lightning("Lightning"), Cactus("Cactus"), Crammed("Crammed"),
        Elytra("Flew into a wall"), Anvil("Falling block"), Command("Killed"), Other("Other");

        public final String title;

        Cause(String title) {
            this.title = title;
        }
    }

    public static final class Death {
        public long time;
        public int x, y, z;
        public String dimension;
        public String server;
        public Cause cause;
        public String killer;
        public String message;
    }

    private static final List<Death> DEATHS = new ArrayList<>();
    private static boolean loaded;
    private static Path loadedFrom;

    private DeathLog() {}

    private static Path path() {
        return PanelFiles.file("deaths.json");
    }

    private static void load() {
        Path path = path();
        if (loaded && path.equals(loadedFrom)) return;
        loaded = true;
        loadedFrom = path;
        DEATHS.clear();
        DEATHS.addAll(PanelFiles.read(path, new TypeToken<List<Death>>() {}.getType(), new ArrayList<Death>()));
    }

    public static synchronized List<Death> all() {
        load();
        List<Death> list = new ArrayList<>(DEATHS);
        list.sort((a, b) -> Long.compare(b.time, a.time));
        return list;
    }

    public static synchronized void clear() {
        load();
        DEATHS.clear();
        PanelFiles.write(path(), DEATHS);
    }

    public static synchronized void remove(Death death) {
        load();
        DEATHS.remove(death);
        PanelFiles.write(path(), DEATHS);
    }

    /** You died, with this message. */
    public static synchronized void record(Text message) {
        load();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        Death d = new Death();
        d.time = System.currentTimeMillis();
        d.x = mc.player.getBlockX();
        d.y = mc.player.getBlockY();
        d.z = mc.player.getBlockZ();
        d.dimension = mc.world.getRegistryKey().getValue().toString();
        d.server = mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address : "singleplayer";
        d.message = message == null ? "" : message.getString();

        String key = null;
        Object[] args = new Object[0];
        if (message != null) {
            TextContent content = message.getContent();
            if (content instanceof TranslatableTextContent t) {
                key = t.getKey();
                args = t.getArgs();
            }
        }

        d.killer = args.length > 1 ? argText(args[1]) : null;
        d.cause = key != null ? fromKey(key, d.killer) : fromWords(d.message.toLowerCase(Locale.ROOT));
        if (d.killer == null && (d.cause == Cause.Player || d.cause == Cause.Mob)) d.killer = killerFromWords(d.message);
        if ((d.cause == Cause.Mob || d.cause == Cause.Player) && d.killer != null) d.cause = isPlayer(d.killer) ? Cause.Player : Cause.Mob;

        DEATHS.add(d);
        PanelFiles.write(path(), DEATHS);
        SessionStats.death();
    }

    private static String argText(Object arg) {
        if (arg instanceof Text t) return t.getString();
        return arg == null ? null : arg.toString();
    }

    private static Cause fromKey(String key, String killer) {
        if (key.startsWith("death.attack.player") || key.startsWith("death.attack.arrow") || key.startsWith("death.attack.trident")
            || key.startsWith("death.attack.mob") || key.startsWith("death.attack.thrown") || key.startsWith("death.attack.sting")
            || key.startsWith("death.attack.mace_smash") || key.startsWith("death.attack.spit")) {
            return killer != null && isPlayer(killer) ? Cause.Player : Cause.Mob;
        }
        if (key.startsWith("death.fell") || key.startsWith("death.attack.fall")) return Cause.Fall;
        if (key.startsWith("death.attack.inFire") || key.startsWith("death.attack.onFire") || key.startsWith("death.attack.hotFloor")
            || key.startsWith("death.attack.campfire") || key.startsWith("death.attack.fireball")) return Cause.Fire;
        if (key.startsWith("death.attack.lava")) return Cause.Lava;
        if (key.startsWith("death.attack.inWall")) return Cause.Suffocation;
        if (key.startsWith("death.attack.cramming")) return Cause.Crammed;
        if (key.startsWith("death.attack.drown") || key.startsWith("death.attack.dryout")) return Cause.Drowning;
        if (key.startsWith("death.attack.explosion") || key.startsWith("death.attack.badRespawnPoint") || key.startsWith("death.attack.fireworks")) return Cause.Explosion;
        if (key.startsWith("death.attack.outOfWorld") || key.startsWith("death.attack.outsideBorder")) return Cause.Void;
        if (key.startsWith("death.attack.starve")) return Cause.Starvation;
        if (key.startsWith("death.attack.wither")) return Cause.Wither;
        if (key.startsWith("death.attack.magic") || key.startsWith("death.attack.indirectMagic") || key.startsWith("death.attack.sonic_boom")) return Cause.Magic;
        if (key.startsWith("death.attack.freeze")) return Cause.Freezing;
        if (key.startsWith("death.attack.lightningBolt")) return Cause.Lightning;
        if (key.startsWith("death.attack.cactus") || key.startsWith("death.attack.sweetBerryBush") || key.startsWith("death.attack.stalagmite")) return Cause.Cactus;
        if (key.startsWith("death.attack.flyIntoWall")) return Cause.Elytra;
        if (key.startsWith("death.attack.anvil") || key.startsWith("death.attack.fallingBlock") || key.startsWith("death.attack.fallingStalactite")) return Cause.Anvil;
        if (key.startsWith("death.attack.genericKill") || key.startsWith("death.attack.generic")) return Cause.Command;
        return Cause.Other;
    }

    /** For servers that send plain text: the usual vanilla wordings. */
    private static Cause fromWords(String m) {
        if (m.contains("was slain by") || m.contains("was shot by") || m.contains("was killed by") || m.contains("was blown up by")
            && !m.contains("intentional")) {
            return m.contains("blown up") ? Cause.Explosion : Cause.Mob;
        }
        if (m.contains("fell") || m.contains("hit the ground")) return Cause.Fall;
        if (m.contains("lava")) return Cause.Lava;
        if (m.contains("burn") || m.contains("flames") || m.contains("fire")) return Cause.Fire;
        if (m.contains("suffocated") || m.contains("in a wall")) return Cause.Suffocation;
        if (m.contains("squished")) return Cause.Crammed;
        if (m.contains("drowned")) return Cause.Drowning;
        if (m.contains("blew up") || m.contains("blown up") || m.contains("explo")) return Cause.Explosion;
        if (m.contains("out of the world") || m.contains("void")) return Cause.Void;
        if (m.contains("starved")) return Cause.Starvation;
        if (m.contains("withered")) return Cause.Wither;
        if (m.contains("magic")) return Cause.Magic;
        if (m.contains("froze")) return Cause.Freezing;
        if (m.contains("lightning")) return Cause.Lightning;
        if (m.contains("pricked") || m.contains("cactus") || m.contains("poked")) return Cause.Cactus;
        if (m.contains("kinetic")) return Cause.Elytra;
        if (m.contains("squashed")) return Cause.Anvil;
        return Cause.Other;
    }

    private static String killerFromWords(String message) {
        for (String marker : new String[] { " by ", " fighting " }) {
            int at = message.indexOf(marker);
            if (at < 0) continue;
            String rest = message.substring(at + marker.length()).trim();
            int end = rest.indexOf(' ');
            return end < 0 ? rest : rest.substring(0, end);
        }
        return null;
    }

    private static boolean isPlayer(String name) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null || name == null) return false;
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(name);
        return entry != null;
    }
}
