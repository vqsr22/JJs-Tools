package dev.jjstools.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.jjstools.JJsTools;
import net.minecraft.util.math.BlockPos;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where your bed spawn is, kept on disk.
 *
 * Stored in .minecraft/jjs-tools/bed-marker.json, outside Meteor's config, so updating anything
 * does not lose it. One entry only: setting a new spawn replaces the old one, same as the game.
 */
public class BedMarkerStore {
    public static class Marker {
        public int x, y, z;
        public String dimension;
        public long setAt;

        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Resolved per call, because the signed-in account can change without a restart. */
    private static Path file() {
        return JJFolder.file("bed-marker.json");
    }

    private static Marker marker;
    private static boolean loaded;
    /** Which account the loaded data belongs to, so switching account reloads rather than mixing. */
    private static java.nio.file.Path loadedFor;

    private BedMarkerStore() {}

    public static synchronized void load() {
        java.nio.file.Path current = file();
        if (loaded && current.equals(loadedFor)) return;

        loaded = true;
        loadedFor = current;

        marker = null;
        JJFolder.migrateIfNeeded("bed-marker.json");

        if (!Files.exists(file())) return;
        try (Reader reader = Files.newBufferedReader(file())) {
            marker = GSON.fromJson(reader, Marker.class);
        } catch (Exception e) {
            JJsTools.LOG.error("Could not read bed-marker.json", e);
        }
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(file().getParent());
            try (Writer writer = Files.newBufferedWriter(file())) {
                GSON.toJson(marker, writer);
            }
        } catch (Exception e) {
            JJsTools.LOG.error("Could not write bed-marker.json", e);
        }
    }

    public static synchronized Marker get() {
        load();
        return marker;
    }

    /** Replaces whatever was there. The game only keeps one bed spawn, so neither do we. */
    public static synchronized void set(BlockPos pos, String dimension) {
        load();

        Marker m = new Marker();
        m.x = pos.getX();
        m.y = pos.getY();
        m.z = pos.getZ();
        m.dimension = dimension;
        m.setAt = System.currentTimeMillis();

        marker = m;
        save();
    }

    public static synchronized void clear() {
        load();
        marker = null;
        save();
    }
}
