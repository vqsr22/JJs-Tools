package dev.jjstools.panel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.jjstools.JJsTools;
import dev.jjstools.util.JJFolder;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Reading and writing the Control Panel's files.
 *
 * Everything the panel keeps about you lives in this account's JJ's Tools folder, under
 * control-panel/, apart from stashes, which go wherever you point them so they can be shared.
 * Files are written to a temporary file first and moved into place, so a crash mid-write never
 * leaves half a file behind.
 */
public final class PanelFiles {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private PanelFiles() {}

    public static Path file(String name) {
        return JJFolder.forAccount().resolve("control-panel").resolve(name);
    }

    public static <T> T read(Path path, Type type, T fallback) {
        if (!Files.exists(path)) return fallback;
        try (Reader reader = Files.newBufferedReader(path)) {
            T value = GSON.fromJson(reader, type);
            return value == null ? fallback : value;
        } catch (Exception e) {
            JJsTools.LOG.error("Control Panel could not read " + path.getFileName() + ", starting fresh", e);
            return fallback;
        }
    }

    public static void write(Path path, Object value) {
        try {
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp)) {
                GSON.toJson(value, writer);
            }
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicNotSupported) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            JJsTools.LOG.error("Control Panel could not write " + path.getFileName(), e);
        }
    }
}
