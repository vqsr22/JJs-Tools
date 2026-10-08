package dev.jjstools.util;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Vanilla 1.21.11 enchantment data, copied from the game's own data pack:
 *   data/minecraft/tags/enchantment/tooltip_order.json
 *   data/minecraft/tags/enchantment/curse.json
 *
 * The tooltip normally reads these from registry tags. Behind ViaFabricPlus those
 * tags can end up pointing at the wrong enchantments, so we look things up by
 * enchantment ID instead, which stays correct.
 */
public final class VanillaEnchants {
    private VanillaEnchants() {}

    private static final List<String> TOOLTIP_ORDER = List.of(
        "minecraft:binding_curse",
        "minecraft:vanishing_curse",
        "minecraft:riptide",
        "minecraft:channeling",
        "minecraft:wind_burst",
        "minecraft:frost_walker",
        "minecraft:lunge",
        "minecraft:sharpness",
        "minecraft:smite",
        "minecraft:bane_of_arthropods",
        "minecraft:impaling",
        "minecraft:power",
        "minecraft:density",
        "minecraft:breach",
        "minecraft:piercing",
        "minecraft:sweeping_edge",
        "minecraft:multishot",
        "minecraft:fire_aspect",
        "minecraft:flame",
        "minecraft:knockback",
        "minecraft:punch",
        "minecraft:protection",
        "minecraft:blast_protection",
        "minecraft:fire_protection",
        "minecraft:projectile_protection",
        "minecraft:feather_falling",
        "minecraft:fortune",
        "minecraft:looting",
        "minecraft:silk_touch",
        "minecraft:luck_of_the_sea",
        "minecraft:efficiency",
        "minecraft:quick_charge",
        "minecraft:lure",
        "minecraft:respiration",
        "minecraft:aqua_affinity",
        "minecraft:soul_speed",
        "minecraft:swift_sneak",
        "minecraft:depth_strider",
        "minecraft:thorns",
        "minecraft:loyalty",
        "minecraft:unbreaking",
        "minecraft:infinity",
        "minecraft:mending"
    );

    private static final Set<String> CURSES = Set.of(
        "minecraft:binding_curse",
        "minecraft:vanishing_curse"
    );

    private static final Map<String, Integer> ORDER_INDEX = new HashMap<>();
    static {
        for (int i = 0; i < TOOLTIP_ORDER.size(); i++) {
            ORDER_INDEX.put(TOOLTIP_ORDER.get(i), i);
        }
    }

    private static final int RED = TextColor.fromFormatting(Formatting.RED).getRgb();
    private static final int GRAY = TextColor.fromFormatting(Formatting.GRAY).getRgb();

    /** "minecraft:mending" style ID, or null if the entry has no key. */
    public static String idOf(RegistryEntry<Enchantment> entry) {
        return entry.getKey().map(key -> key.getValue().toString()).orElse(null);
    }

    /** Position in the vanilla tooltip order. Unknown (modded) enchantments sort after all vanilla ones. */
    public static int orderIndex(RegistryEntry<Enchantment> entry) {
        String id = idOf(entry);
        if (id == null) return Integer.MAX_VALUE;
        return ORDER_INDEX.getOrDefault(id, Integer.MAX_VALUE);
    }

    /**
     * Recolours an enchantment name the way vanilla would: red for curses, grey for the rest.
     * Only touches vanilla enchantments, and only if the name is currently plain red, plain grey
     * or uncoloured, so custom coloured names from data packs are left alone.
     */
    public static void fixColour(RegistryEntry<Enchantment> entry, Text name) {
        String id = idOf(entry);
        if (id == null || !ORDER_INDEX.containsKey(id)) return; // modded or unknown: leave vanilla behaviour
        if (!(name instanceof MutableText mutable)) return;

        Style style = mutable.getStyle();
        TextColor current = style.getColor();
        if (current != null && current.getRgb() != RED && current.getRgb() != GRAY) return;

        Formatting wanted = CURSES.contains(id) ? Formatting.RED : Formatting.GRAY;
        mutable.setStyle(style.withColor(wanted));
    }
}
