package dev.jjstools.util;

import dev.jjstools.JJsTools;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.equipment.trim.ArmorTrim;
import net.minecraft.item.equipment.trim.ArmorTrimMaterial;
import net.minecraft.item.equipment.trim.ArmorTrimPattern;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;

import java.util.Optional;

/**
 * JJ's Tools' icon: an enchanted netherite helmet with a gold trim, used for the add-on's
 * category in Meteor and at the top of the Control Panel.
 *
 * Armour trims live in the world's registries, which only exist once you are in a world, so the
 * icon starts as the enchanted helmet and gets its gold trim the first time a world is loaded.
 * The same item is used everywhere, so adding the trim to it shows it everywhere at once. If
 * you have picked a different icon in the Icons tab, that is left alone.
 */
public final class AddonIcon {
    private static Object trimmedFor;

    private AddonIcon() {}

    /** The helmet, glinting, before it can be trimmed. */
    public static ItemStack create() {
        ItemStack stack = Items.NETHERITE_HELMET.getDefaultStack();
        stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        return stack;
    }

    /** Called every tick: trims the icon once per world, as soon as there is one. */
    public static void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || trimmedFor == mc.world) return;
        trimmedFor = mc.world;

        ItemStack icon = JJsTools.CATEGORY.icon;
        if (icon == null || !icon.isOf(Items.NETHERITE_HELMET)) return;

        try {
            var registries = mc.world.getRegistryManager();
            Optional<RegistryEntry.Reference<ArmorTrimMaterial>> gold = registries.getOrThrow(RegistryKeys.TRIM_MATERIAL)
                .getOptional(RegistryKey.of(RegistryKeys.TRIM_MATERIAL, Identifier.ofVanilla("gold")));
            Optional<RegistryEntry.Reference<ArmorTrimPattern>> pattern = registries.getOrThrow(RegistryKeys.TRIM_PATTERN)
                .getOptional(RegistryKey.of(RegistryKeys.TRIM_PATTERN, Identifier.ofVanilla("sentry")));
            if (gold.isPresent() && pattern.isPresent()) icon.set(DataComponentTypes.TRIM, new ArmorTrim(gold.get(), pattern.get()));
        } catch (Throwable ignored) {
            // Stays an untrimmed helmet.
        }
    }
}
