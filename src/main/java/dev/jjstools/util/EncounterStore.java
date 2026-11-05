package dev.jjstools.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.jjstools.JJsTools;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every player you have ever seen, kept on disk.
 *
 * Stored in .minecraft/jjs-tools/encounters.json rather than inside Meteor's config, so updating
 * Meteor or JJ's Tools, or wiping a profile, does not take the list with it.
 */
public class EncounterStore {
    public static class Encounter {
        public String name;
        public String uuid;
        public long firstSeen;
        public long lastSeen;
        public int count;
        public int x, y, z;
        public String dimension;

        public UUID id() {
            try {
                return uuid == null ? null : UUID.fromString(uuid);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Resolved per call, because the signed-in account can change without a restart. */
    private static Path file() {
        return JJFolder.file("encounters.json");
    }

    private static final Map<String, Encounter> BY_NAME = new LinkedHashMap<>();
    private static boolean loaded;
    /** Which account the loaded data belongs to, so switching account reloads rather than mixing. */
    private static java.nio.file.Path loadedFor;

    private EncounterStore() {}

    public static synchronized void load() {
        java.nio.file.Path current = file();
        if (loaded && current.equals(loadedFor)) return;

        loaded = true;
        loadedFor = current;

        BY_NAME.clear();
        JJFolder.migrateIfNeeded("encounters.json");

        if (!Files.exists(file())) return;
        try (Reader reader = Files.newBufferedReader(file())) {
            List<Encounter> list = GSON.fromJson(reader, new TypeToken<List<Encounter>>() {}.getType());
            if (list == null) return;
            for (Encounter e : list) {
                if (e != null && e.name != null) BY_NAME.put(e.name.toLowerCase(), e);
            }
        } catch (Exception e) {
            JJsTools.LOG.error("Could not read encounters.json, starting from empty", e);
        }
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(file().getParent());
            try (Writer writer = Files.newBufferedWriter(file())) {
                GSON.toJson(new ArrayList<>(BY_NAME.values()), writer);
            }
        } catch (IOException e) {
            JJsTools.LOG.error("Could not write encounters.json", e);
        }
    }

    /** Records a sighting, creating the entry if this is the first one. */
    public static synchronized void record(String name, UUID id, int x, int y, int z, String dimension) {
        load();
        long now = System.currentTimeMillis();
        Encounter e = BY_NAME.get(name.toLowerCase());

        if (e == null) {
            e = new Encounter();
            e.name = name;
            e.firstSeen = now;
            e.count = 0;
            BY_NAME.put(name.toLowerCase(), e);
        }

        if (id != null) e.uuid = id.toString();
        e.lastSeen = now;
        e.count++;
        e.x = x; e.y = y; e.z = z;
        e.dimension = dimension;
    }

    public static synchronized List<Encounter> all() {
        load();
        List<Encounter> list = new ArrayList<>(BY_NAME.values());
        list.sort(Comparator.comparingLong((Encounter e) -> e.lastSeen).reversed());
        return list;
    }

    public static synchronized void remove(Encounter encounter) {
        load();
        if (encounter.name != null) BY_NAME.remove(encounter.name.toLowerCase());
        save();
    }

    public static synchronized int size() {
        load();
        return BY_NAME.size();
    }
}
