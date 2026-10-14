package dev.jjstools.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.jjstools.JJsTools;
import it.unimi.dsi.fastutil.objects.Object2IntArrayMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * The scanned kit: what was in a shulker box, slot by slot.
 *
 * Only two things about an item are recorded, because they are the only two that decide whether a
 * replacement is the same item: what it is, and what is enchanted onto it. Names, lore, durability
 * and everything else are deliberately ignored, so a freshly crafted pick fills the slot a worn,
 * renamed one came out of.
 *
 * Enchantments are kept as plain IDs ("minecraft:mending") rather than registry entries. Matching
 * then never has to resolve anything back out of the registry, which is what makes this survive a
 * restart and a protocol translation layer.
 *
 * Kept in .minecraft/jjs-tools/accounts/&lt;uuid&gt;/kit.json, so it is per account and outlives
 * updates.
 */
public class KitStore {
    private static final String FILE = "kit.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** One slot's worth of item, reduced to what matching actually cares about. */
    public static class Spec {
        public String item;
        public int count;
        public Map<String, Integer> enchants;

        public Map<String, Integer> enchants() {
            return enchants == null ? Map.of() : enchants;
        }
    }

    public static class Kit {
        public int size;
        public long scanned;
        public Spec[] slots;

        public int filledSlots() {
            if (slots == null) return 0;
            int n = 0;
            for (Spec spec : slots) if (spec != null) n++;
            return n;
        }
    }

    private static Kit kit;
    private static boolean loaded;

    private KitStore() {}

    /** The scanned kit, or null if nothing has been scanned on this account yet. */
    public static Kit kit() {
        if (!loaded) {
            loaded = true;
            load();
        }
        return kit;
    }

    public static void set(Kit newKit) {
        loaded = true;
        kit = newKit;
        save();
    }

    public static void clear() {
        loaded = true;
        kit = null;

        try {
            Files.deleteIfExists(JJFolder.file(FILE));
        } catch (IOException e) {
            JJsTools.LOG.error("Could not delete the scanned kit", e);
        }
    }

    private static void load() {
        Path path = JJFolder.file(FILE);
        if (!Files.exists(path)) return;

        try (Reader reader = Files.newBufferedReader(path)) {
            kit = GSON.fromJson(reader, Kit.class);
        } catch (Exception e) {
            JJsTools.LOG.error("Could not read the scanned kit", e);
            kit = null;
        }

        if (kit != null && kit.slots == null) kit = null;
    }

    private static void save() {
        Path path = JJFolder.file(FILE);

        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(kit, writer);
            }
        } catch (IOException e) {
            JJsTools.LOG.error("Could not save the scanned kit", e);
        }
    }

    // Reading items

    /** The spec for a stack, or null for an empty slot. */
    public static Spec specOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;

        Spec spec = new Spec();
        spec.item = idOf(stack);
        spec.count = stack.getCount();
        spec.enchants = enchantsOf(stack);
        return spec;
    }

    public static String idOf(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()).toString();
    }

    /**
     * Enchantment IDs to levels. Covers both the enchantments on gear and the ones stored in a
     * book, because Meteor's helper reads whichever component the item actually carries.
     */
    public static Map<String, Integer> enchantsOf(ItemStack stack) {
        Map<String, Integer> out = new TreeMap<>();
        if (stack == null || stack.isEmpty()) return out;

        Object2IntMap<RegistryEntry<Enchantment>> found = new Object2IntArrayMap<>();
        Utils.getEnchantments(stack, found);

        for (Object2IntMap.Entry<RegistryEntry<Enchantment>> entry : found.object2IntEntrySet()) {
            String id = VanillaEnchants.idOf(entry.getKey());
            if (id != null && entry.getIntValue() > 0) out.put(id, entry.getIntValue());
        }

        return out;
    }

    /** True if this stack would fill the slot the spec came from. */
    public static boolean matches(Spec spec, ItemStack stack, boolean checkEnchants) {
        if (spec == null || spec.item == null || stack == null || stack.isEmpty()) return false;
        if (!spec.item.equals(idOf(stack))) return false;
        if (!checkEnchants) return true;

        return enchantsOf(stack).equals(spec.enchants());
    }
}
