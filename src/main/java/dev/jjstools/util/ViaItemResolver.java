package dev.jjstools.util;

import net.minecraft.client.resource.language.I18n;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Works out which item ViaBackwards actually meant.
 *
 * When the server sends something the old protocol has no id for, ViaBackwards substitutes the
 * nearest item it does have and renames it, so a pale oak sign arrives as minecraft:birch_sign
 * called "1.21.2 Pale Oak Sign". The original id is NOT kept anywhere on the stack: the VB| backup
 * tag only holds the display name so the name can be restored on the way back.
 *
 * So the name is all there is to go on. The client is a full 1.21.11 client and knows every item,
 * so the display name is matched back against the item registry's own translated names.
 */
public class ViaItemResolver {
    /** Translated display name, lowercased, to the item that owns it. Built once on first use. */
    private static volatile Map<String, Item> byName;

    /** Name to resolved item, including misses, so a failed lookup is only paid for once. */
    private static final Map<String, Item> CACHE = new ConcurrentHashMap<>();

    private ViaItemResolver() {}

    private static Map<String, Item> names() {
        Map<String, Item> map = byName;
        if (map != null) return map;

        synchronized (ViaItemResolver.class) {
            if (byName != null) return byName;

            Map<String, Item> built = new HashMap<>();
            for (Item item : Registries.ITEM) {
                if (item == Items.AIR) continue;
                String translated = I18n.translate(item.getTranslationKey());
                if (translated == null || translated.isBlank()) continue;
                // First one wins: several items can share a display name and the earlier
                // registry entry is the vanilla one.
                built.putIfAbsent(translated.toLowerCase(Locale.ROOT), item);
            }
            byName = built;
            return built;
        }
    }

    /**
     * The item this stack was before translation, or null if the name does not name a different
     * item. The name passed in should already have had its version prefix stripped.
     */
    public static Item resolve(String strippedName, ItemStack stack) {
        if (strippedName == null || strippedName.isBlank()) return null;

        String key = strippedName.toLowerCase(Locale.ROOT);
        Item cached = CACHE.get(key);
        if (cached != null) return cached == Items.AIR ? null : cached;

        Item item = names().get(key);
        // AIR is the "no match" marker so misses are cached too.
        CACHE.put(key, item == null ? Items.AIR : item);

        if (item == null || item == stack.getItem()) return null;
        return item;
    }

    /** Clears the caches, for a resource reload or language change. */
    public static void invalidate() {
        byName = null;
        CACHE.clear();
    }
}
