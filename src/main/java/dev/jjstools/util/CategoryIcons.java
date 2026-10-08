package dev.jjstools.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Custom icons for every module category, whichever addon owns it.
 *
 * Saved to .minecraft/jjs-tools/category-icons.json so updates do not wipe them, and applied over
 * whatever the owning addon set.
 */
public class CategoryIcons {
    public static class IconEntry {
        public String item;
        public boolean glint;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getGameDir()
        .resolve("jjs-tools").resolve("category-icons.json");

    private static final Map<String, IconEntry> ENTRIES = new LinkedHashMap<>();
    /** What each category looked like before we touched it, so "reset" is real. */
    private static final Map<String, ItemStack> ORIGINALS = new LinkedHashMap<>();
    private static boolean loaded;

    private CategoryIcons() {}

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        if (!Files.exists(FILE)) return;

        try (Reader reader = Files.newBufferedReader(FILE)) {
            Map<String, IconEntry> map = GSON.fromJson(reader, new TypeToken<Map<String, IconEntry>>() {}.getType());
            if (map != null) ENTRIES.putAll(map);
        } catch (Exception e) {
            JJsTools.LOG.error("Could not read category-icons.json", e);
        }
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                GSON.toJson(ENTRIES, writer);
            }
        } catch (Exception e) {
            JJsTools.LOG.error("Could not write category-icons.json", e);
        }
    }

    /**
     * Some items glint on their own, a nether star being the obvious one. For those the toggle is
     * meaningless, so it is ignored rather than doing something confusing.
     */
    public static boolean glintsNaturally(Item item) {
        return item.getDefaultStack().hasGlint();
    }

    public static ItemStack stackFor(Item item, boolean glint) {
        ItemStack stack = item.getDefaultStack();
        if (glint && !glintsNaturally(item)) {
            stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return stack;
    }

    public static synchronized Item itemFor(Category category) {
        load();
        IconEntry entry = ENTRIES.get(category.name);
        if (entry == null || entry.item == null) return originalItem(category);

        Identifier id = Identifier.tryParse(entry.item);
        if (id == null) return originalItem(category);
        Item item = Registries.ITEM.get(id);
        return item == Items.AIR ? originalItem(category) : item;
    }

    public static synchronized boolean glintFor(Category category) {
        load();
        IconEntry entry = ENTRIES.get(category.name);
        return entry != null && entry.glint;
    }

    private static Item originalItem(Category category) {
        ItemStack original = ORIGINALS.get(category.name);
        return original == null ? category.icon.getItem() : original.getItem();
    }

    public static synchronized void set(Category category, Item item, boolean glint) {
        load();
        remember(category);

        IconEntry entry = ENTRIES.computeIfAbsent(category.name, k -> new IconEntry());
        entry.item = Registries.ITEM.getId(item).toString();
        entry.glint = glint;

        apply(category);
        save();
    }

    public static synchronized void reset(Category category) {
        load();
        ENTRIES.remove(category.name);

        ItemStack original = ORIGINALS.get(category.name);
        if (original != null) ((ICategoryIcon) category).jjsTools$setIcon(original.copy());
        save();
    }

    private static void remember(Category category) {
        ORIGINALS.putIfAbsent(category.name, category.icon.copy());
    }

    private static void apply(Category category) {
        IconEntry entry = ENTRIES.get(category.name);
        if (entry == null || entry.item == null) return;

        Identifier id = Identifier.tryParse(entry.item);
        if (id == null) return;
        Item item = Registries.ITEM.get(id);
        if (item == Items.AIR) return;

        ((ICategoryIcon) category).jjsTools$setIcon(stackFor(item, entry.glint));
    }

    /** Applied once every category from every addon has been registered. */
    public static synchronized void applyAll() {
        load();
        for (Category category : Modules.loopCategories()) {
            remember(category);
            apply(category);
        }
    }
}
