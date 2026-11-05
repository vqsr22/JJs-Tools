package dev.jjstools.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.item.ItemStack;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Where everything in your inventory was sitting, slot by slot.
 *
 * Two things decide whether an item can take another one's place: what it is, and whether it digs
 * the same way. Silk Touch and Fortune change what a tool is for, so a silk pick and a fortune
 * pick are not interchangeable and the loadout has to tell them apart. Every other enchantment,
 * and the name, is ignored, because a Unbreaking III pick and a Unbreaking II one do the same job
 * and you do not want the loadout refusing to use the one you have.
 *
 * Kept in .minecraft/jjs-tools/accounts/&lt;uuid&gt;/loadout.json, so it is per account and survives
 * updates.
 */
public class LoadoutStore {
    private static final String FILE = "loadout.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** The only enchantments that make two of the same item different for this purpose. */
    private static final Set<String> THAT_MATTER = Set.of("minecraft:silk_touch", "minecraft:fortune");

    public static class Spec {
        public String item;
        public int count;
        public Map<String, Integer> enchants;

        public Map<String, Integer> enchants() {
            return enchants == null ? Map.of() : enchants;
        }
    }

    public static class Loadout {
        public long saved;
        public Spec[] slots;

        /**
         * The screen handler slot that slots[0] refers to.
         *
         * Written down rather than assumed, because the range this covers has changed once
         * already: it used to start at the main inventory and now starts at your armour. Without
         * this, a file saved before that change would be read one layout against another and put
         * your hotbar on your head.
         */
        public int firstSlot;

        /** Files written before armour was included have no firstSlot and began at the main rows. */
        public int firstSlot() {
            return firstSlot == 0 ? 9 : firstSlot;
        }

        public int filledSlots() {
            if (slots == null) return 0;
            int n = 0;
            for (Spec spec : slots) if (spec != null) n++;
            return n;
        }
    }

    private static Loadout loadout;
    private static boolean loaded;

    private LoadoutStore() {}

    public static Loadout loadout() {
        if (!loaded) {
            loaded = true;
            load();
        }
        return loadout;
    }

    public static void set(Loadout newLoadout) {
        loaded = true;
        loadout = newLoadout;
        save();
    }

    private static void load() {
        Path path = JJFolder.file(FILE);
        if (!Files.exists(path)) return;

        try (Reader reader = Files.newBufferedReader(path)) {
            loadout = GSON.fromJson(reader, Loadout.class);
        } catch (Exception e) {
            dev.jjstools.JJsTools.LOG.error("Could not read the saved loadout", e);
            loadout = null;
        }

        if (loadout != null && loadout.slots == null) loadout = null;
    }

    private static void save() {
        Path path = JJFolder.file(FILE);

        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(loadout, writer);
            }
        } catch (IOException e) {
            dev.jjstools.JJsTools.LOG.error("Could not save the loadout", e);
        }
    }

    // Reading items

    /** The spec for a stack, or null for an empty slot. */
    public static Spec specOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;

        Spec spec = new Spec();
        spec.item = KitStore.idOf(stack);
        spec.count = stack.getCount();
        spec.enchants = diggingEnchants(stack);
        return spec;
    }

    /** Silk Touch and Fortune only, read from whatever the stack actually carries. */
    public static Map<String, Integer> diggingEnchants(ItemStack stack) {
        Map<String, Integer> out = new TreeMap<>();
        if (stack == null || stack.isEmpty()) return out;

        for (Map.Entry<String, Integer> entry : KitStore.enchantsOf(stack).entrySet()) {
            if (THAT_MATTER.contains(entry.getKey())) out.put(entry.getKey(), entry.getValue());
        }

        return out;
    }

    /** True if this stack would do for the slot the spec came from. */
    public static boolean matches(Spec spec, ItemStack stack) {
        if (spec == null || spec.item == null || stack == null || stack.isEmpty()) return false;
        if (!spec.item.equals(KitStore.idOf(stack))) return false;

        return diggingEnchants(stack).equals(spec.enchants());
    }
}
