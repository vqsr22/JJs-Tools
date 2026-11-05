package dev.jjstools.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.jjstools.JJsTools;
import dev.jjstools.mixin.accessor.ModuleNameAccessor;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Renaming modules so two addons can both use one that happens to share a name.
 *
 * Meteor identifies a module by its name, not by what registered it, so two addons shipping a
 * "Nuker" give you two entries you cannot tell apart, one keybind that only ever reaches one of
 * them, and two sets of settings saved under the same key. Renaming one of them separates all
 * three at once.
 *
 * Renames are keyed by the module's class rather than its name, since the name is the thing being
 * changed and the class is what stays put across launches and updates. Stored in
 * .minecraft/jjs-tools/module-renames.json, shared across accounts: a clash is a property of which
 * addons are installed, not of who is signed in.
 */
public class ModuleRenames {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getGameDir()
        .resolve("jjs-tools").resolve("module-renames.json");

    /** Module class name to the name it should carry. */
    private static final Map<String, String> RENAMES = new LinkedHashMap<>();

    /** What each renamed module was called when we first saw it, so "reset" is real. */
    private static final Map<String, String> ORIGINALS = new LinkedHashMap<>();

    /** Every module we saw registered, in order, including ones Meteor would have replaced. */
    private static final List<Module> REGISTERED = new ArrayList<>();

    /** Current name, lowercased, to the module holding it. */
    private static final Map<String, Module> TAKEN = new LinkedHashMap<>();

    /** Modules this renamed by itself to stop a clash, keyed by class. */
    private static final Map<String, String> AUTO = new LinkedHashMap<>();

    private static boolean loaded;

    private ModuleRenames() {}

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        if (!Files.exists(FILE)) return;

        try (Reader reader = Files.newBufferedReader(FILE)) {
            Map<String, String> map = GSON.fromJson(reader, new TypeToken<Map<String, String>>() {}.getType());
            if (map != null) RENAMES.putAll(map);
        } catch (Exception e) {
            JJsTools.LOG.error("Could not read module-renames.json", e);
        }
    }

    private static synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                GSON.toJson(RENAMES, writer);
            }
        } catch (Exception e) {
            JJsTools.LOG.error("Could not save module-renames.json", e);
        }
    }

    /**
     * Called for every module as it is registered, from ModulesAddMixin.
     *
     * Three things happen in order, and the order is the whole of it. A name you chose is put back
     * first, so your choice is what the clash test sees. Then, if that name is already spoken for,
     * this one is given a name of its own rather than being allowed to replace what is there.
     * Only then is it recorded.
     */
    public static synchronized void onRegister(Module module) {
        load();
        REGISTERED.add(module);

        String stored = RENAMES.get(key(module));
        if (stored != null && !stored.isBlank() && !stored.equals(module.name)) {
            ORIGINALS.putIfAbsent(key(module), module.name);
            apply(module, stored);
        }

        String lower = module.name.toLowerCase(Locale.ROOT);
        Module holder = TAKEN.get(lower);

        if (holder != null && holder != module) {
            ORIGINALS.putIfAbsent(key(module), module.name);

            String unique = freeName(module.name, module);
            AUTO.put(key(module), unique);
            apply(module, unique);
            lower = unique.toLowerCase(Locale.ROOT);

            JJsTools.LOG.info("Renamed {} to {} so it does not replace {} from {}",
                originalName(module), unique, holder.getClass().getName(), sourceOf(holder));
        }

        TAKEN.put(lower, module);
    }

    /** The wanted name with the add-on it came from on the end, numbered if that is taken too. */
    private static String freeName(String wanted, Module module) {
        String candidate = wanted + "-" + sourceOf(module).toLowerCase(Locale.ROOT).replace(' ', '-');

        if (!TAKEN.containsKey(candidate.toLowerCase(Locale.ROOT))) return candidate;

        for (int n = 2; n < 100; n++) {
            String numbered = candidate + "-" + n;
            if (!TAKEN.containsKey(numbered.toLowerCase(Locale.ROOT))) return numbered;
        }

        return candidate + "-" + System.identityHashCode(module);
    }

    public static boolean isAutoRenamed(Module module) {
        return AUTO.containsKey(key(module));
    }

    /**
     * Every module, taken from what we watched register rather than from Meteor.
     *
     * Asking Meteor would only ever return the survivors of a clash, and the ones that lost are
     * exactly what this is for. Falls back to asking if nothing was recorded, which means the
     * registration hook did not run.
     */
    public static List<Module> allModules() {
        if (!REGISTERED.isEmpty()) return new ArrayList<>(REGISTERED);

        List<Module> all = new ArrayList<>();
        for (Category category : Modules.loopCategories()) {
            all.addAll(Modules.get().getGroup(category));
        }

        return all;
    }

    /**
     * Names used by more than one module, with the modules that use them.
     *
     * Compared without case, because two modules differing only in capitalisation are just as
     * ambiguous to look at and to type at a command.
     */
    public static Map<String, List<Module>> conflicts() {
        Map<String, List<Module>> byName = new LinkedHashMap<>();

        // Grouped by the name each one was given by its own add-on, not the name it carries now.
        // Once a clash has been settled the current names differ, and grouping by those would show
        // nothing at all.
        for (Module module : allModules()) {
            byName.computeIfAbsent(originalName(module).toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(module);
        }

        byName.values().removeIf(list -> list.size() < 2);
        return byName;
    }

    /** The name this module was given by its own addon. */
    public static String originalName(Module module) {
        return ORIGINALS.getOrDefault(key(module), module.name);
    }

    public static boolean isRenamed(Module module) {
        return RENAMES.containsKey(key(module));
    }

    /**
     * Renames a module now and remembers it for next launch.
     *
     * A blank or unchanged name puts it back to whatever its addon called it.
     */
    public static synchronized void rename(Module module, String name) {
        String trimmed = name == null ? "" : name.trim();
        String original = originalName(module);

        if (trimmed.isBlank() || trimmed.equals(original)) {
            RENAMES.remove(key(module));

            // Putting back the original would recreate the clash, so an auto name stands until
            // the other side is the one that moves.
            apply(module, AUTO.getOrDefault(key(module), original));
        }
        else {
            ORIGINALS.putIfAbsent(key(module), module.name);
            RENAMES.put(key(module), trimmed);
            AUTO.remove(key(module));
            apply(module, trimmed);
        }

        save();
    }

    /**
     * Puts the stored names back onto their modules.
     *
     * Run more than once on purpose. Addons are initialised in turn, so one running before this
     * one has its modules renamed at startup, while one running after is only caught the next time
     * the tab is opened.
     */
    public static synchronized void applyAll() {
        load();
        if (RENAMES.isEmpty()) return;

        for (Module module : allModules()) {
            String wanted = RENAMES.get(key(module));
            if (wanted == null || wanted.equals(module.name)) continue;

            ORIGINALS.putIfAbsent(key(module), module.name);
            apply(module, wanted);
        }
    }

    private static void apply(Module module, String name) {
        ((ModuleNameAccessor) module).jjsTools$setName(name);
    }

    private static String key(Module module) {
        return module.getClass().getName();
    }

    /**
     * Where a module came from, read off its package.
     *
     * Meteor does not record which add-on registered what, so the package is the only thing left
     * that says. The first segment is almost always com, net, me or similar and tells you nothing,
     * so the segment after it is the one worth showing.
     */
    public static String sourceOf(Module module) {
        String className = module.getClass().getName();
        if (className.startsWith("meteordevelopment.meteorclient.")) return "Meteor";

        String[] parts = className.split("\\.");
        if (parts.length == 1) return className;

        int pick = GENERIC_PREFIXES.contains(parts[0].toLowerCase(Locale.ROOT)) && parts.length > 2 ? 1 : 0;
        return parts[pick];
    }

    private static final Set<String> GENERIC_PREFIXES =
        Set.of("com", "net", "org", "io", "me", "dev", "xyz", "eu", "fr", "de", "co", "club", "gg");
}
