package dev.jjstools.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.jjstools.JJsTools;
import net.minecraft.util.math.BlockPos;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Positions already reported, kept on disk per account.
 *
 * Without this, flying back over somewhere you have already searched pings you for the same
 * obsidian all over again, and a relog resets the lot. Holding it in memory alone was never going
 * to be enough.
 *
 * Keyed by dimension as well as position, since nether and overworld coordinates overlap.
 */
public class SeenStore {
    private static final Gson GSON = new GsonBuilder().create();

    /** file name to the set of packed positions it holds. */
    private static final Map<String, Set<Long>> CACHE = new ConcurrentHashMap<>();
    private static Path loadedFor;

    private SeenStore() {}

    private static Path file(String name) {
        return JJFolder.file(name + ".json");
    }

    private static synchronized Set<Long> set(String name) {
        Path current = JJFolder.forAccount();

        // Switching account must not inherit the previous one's history.
        if (loadedFor == null || !loadedFor.equals(current)) {
            CACHE.clear();
            loadedFor = current;
        }

        return CACHE.computeIfAbsent(name, SeenStore::read);
    }

    private static Set<Long> read(String name) {
        Path path = file(name);
        if (!Files.exists(path)) return new HashSet<>();

        try (Reader reader = Files.newBufferedReader(path)) {
            Set<Long> set = GSON.fromJson(reader, new TypeToken<Set<Long>>() {}.getType());
            return set == null ? new HashSet<>() : set;
        } catch (Exception e) {
            JJsTools.LOG.error("Could not read {}.json", name, e);
            return new HashSet<>();
        }
    }

    private static void write(String name, Set<Long> set) {
        try {
            Path path = file(name);
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(set, writer);
            }
        } catch (Exception e) {
            JJsTools.LOG.error("Could not write {}.json", name, e);
        }
    }

    /**
     * Records a position and says whether it was new.
     *
     * One call does both so there is no window between checking and adding, which matters when the
     * instant block-update path and the chunk scan can both reach the same block.
     */
    public static synchronized boolean addIfNew(String name, String dimension, BlockPos pos) {
        Set<Long> set = set(key(name, dimension));

        if (!set.add(pos.asLong())) return false;

        write(key(name, dimension), set);
        return true;
    }

    public static synchronized boolean contains(String name, String dimension, BlockPos pos) {
        return set(key(name, dimension)).contains(pos.asLong());
    }

    public static synchronized void clear(String name, String dimension) {
        String key = key(name, dimension);
        set(key).clear();
        write(key, CACHE.get(key));
    }

    public static synchronized int size(String name, String dimension) {
        return set(key(name, dimension)).size();
    }

    private static String key(String name, String dimension) {
        return name + "-" + dimension;
    }
}
