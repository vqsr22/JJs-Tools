package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.equipment.trim.ArmorTrim;
import net.minecraft.item.equipment.trim.ArmorTrimMaterial;
import net.minecraft.item.equipment.trim.ArmorTrimPattern;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Identifier;

import java.util.Locale;
import java.util.Optional;

/**
 * Client-side armour trims. Shows a chosen trim on your own armour (on your player model and on
 * the armour icons) without changing the real items. Nobody else sees it.
 */
public class CustomTrims extends Module {
    public enum Pattern { Sentry, Vex, Wild, Coast, Dune, Wayfinder, Raiser, Shaper, Host, Ward, Silence, Tide, Snout, Rib, Eye, Spire, Flow, Bolt }
    public enum Material { Quartz, Iron, Netherite, Redstone, Copper, Gold, Emerald, Diamond, Lapis, Amethyst, Resin }


    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> showOnPlayer = sgGeneral.add(new BoolSetting.Builder()
        .name("show-on-player")
        .description("Show the trims on your armour on your player (third person, F5 and the inventory model).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> changeIcons = sgGeneral.add(new BoolSetting.Builder()
        .name("change-item-icons")
        .description("Also show the trim colour on the icons of the armour you are wearing (inventory, hotbar). Icons use the vanilla trim colours.")
        .defaultValue(true)
        .build()
    );


    private final Piece helmet = new Piece("Helmet", "helmet", EquipmentSlot.HEAD, Pattern.Flow, Material.Gold);
    private final Piece chestplate = new Piece("Chestplate", "chestplate", EquipmentSlot.CHEST, Pattern.Eye, Material.Diamond);
    private final Piece leggings = new Piece("Leggings", "leggings", EquipmentSlot.LEGS, Pattern.Silence, Material.Gold);
    private final Piece boots = new Piece("Boots", "boots", EquipmentSlot.FEET, Pattern.Snout, Material.Gold);

    public CustomTrims() {
        super(JJsTools.CATEGORY, "custom-trims", "Client-side armour trims on your own armour. Only you can see them.");
    }

    /** One armour piece's settings. */
    private final class Piece {
        final EquipmentSlot slot;
        final Setting<Boolean> enabled;
        final Setting<Pattern> pattern;
        final Setting<Material> material;

        Piece(String title, String id, EquipmentSlot slot, Pattern defaultPattern, Material defaultMaterial) {
            this.slot = slot;
            SettingGroup g = settings.createGroup(title);

            enabled = g.add(new BoolSetting.Builder()
                .name(id)
                .description("Show a cosmetic trim on your " + id + ".")
                .defaultValue(true)
                .build()
            );
            pattern = g.add(new EnumSetting.Builder<Pattern>()
                .name(id + "-trim")
                .description("Trim pattern for your " + id + ".")
                .defaultValue(defaultPattern)
                .visible(enabled::get)
                .build()
            );
            material = g.add(new EnumSetting.Builder<Material>()
                .name(id + "-colour")
                .description("Trim colour (trim material) for your " + id + ".")
                .defaultValue(defaultMaterial)
                .visible(enabled::get)
                .build()
            );
        }

        Material effective() {
            return material.get();
        }

    }

    // Used by the mixins

    private static CustomTrims active() {
        Modules modules = Modules.get();
        if (modules == null) return null;
        CustomTrims m = modules.get(CustomTrims.class);
        return m != null && m.isActive() ? m : null;
    }

    private Piece piece(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> helmet;
            case CHEST -> chestplate;
            case LEGS -> leggings;
            case FEET -> boots;
            default -> null;
        };
    }

    // Player model: your render state gets trimmed copies of your armour (see BipedEntityRendererMixin).
    // The copies only exist for drawing; your real items are never touched.

    private final ItemStack[] cachedOriginal = new ItemStack[4];
    private final ArmorTrim[] cachedTrim = new ArmorTrim[4];
    private final ItemStack[] cachedCopy = new ItemStack[4];

    /** Your armour piece with the cosmetic trim on it, or the original if there is nothing to change. */
    public static ItemStack trimmedForPlayer(ItemStack original, EquipmentSlot slot) {
        CustomTrims m = active();
        if (m == null || !m.showOnPlayer.get() || original == null || original.isEmpty()) return original;
        // Only armour that can really be trimmed. An elytra has no trim textures and draws your cape,
        // so giving it a trim covered the cape in the missing-texture pattern.
        if (!original.isIn(ItemTags.TRIMMABLE_ARMOR)) return original;
        Piece p = m.piece(slot);
        if (p == null || !p.enabled.get()) return original;

        ArmorTrim trim = m.makeTrim(p.pattern.get(), p.effective());
        if (trim == null) return original;

        int i = slot.getEntitySlotId();
        if (i < 0 || i > 3) return original;
        if (m.cachedOriginal[i] != original || !trim.equals(m.cachedTrim[i]) || m.cachedCopy[i] == null) {
            ItemStack copy = original.copy();
            copy.set(DataComponentTypes.TRIM, trim);
            m.cachedOriginal[i] = original;
            m.cachedTrim[i] = trim;
            m.cachedCopy[i] = copy;
        }
        return m.cachedCopy[i];
    }

    /** Trim material to show on an item icon, or null to leave it alone. Only for armour you are wearing. */
    public static RegistryKey<ArmorTrimMaterial> iconMaterial(ItemStack stack) {
        CustomTrims m = active();
        if (m == null || !m.changeIcons.get() || m.mc.player == null || stack.isEmpty()) return null;

        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (m.mc.player.getEquippedStack(slot) != stack) continue;
            if (!stack.isIn(ItemTags.TRIMMABLE_ARMOR)) return null;
            Piece p = m.piece(slot);
            if (p == null || !p.enabled.get()) return null;
            return materialKey(p.effective());
        }
        return null;
    }

    private ArmorTrim makeTrim(Pattern pattern, Material material) {
        if (mc.world == null) return null;
        try {
            Optional<RegistryEntry.Reference<ArmorTrimMaterial>> mat = mc.world.getRegistryManager()
                .getOrThrow(RegistryKeys.TRIM_MATERIAL).getOptional(materialKey(material));
            Optional<RegistryEntry.Reference<ArmorTrimPattern>> pat = mc.world.getRegistryManager()
                .getOrThrow(RegistryKeys.TRIM_PATTERN).getOptional(RegistryKey.of(RegistryKeys.TRIM_PATTERN, id(pattern.name())));
            if (mat.isEmpty() || pat.isEmpty()) return null; // server does not have this trim
            return new ArmorTrim(mat.get(), pat.get());
        } catch (Throwable t) {
            return null;
        }
    }

    private static RegistryKey<ArmorTrimMaterial> materialKey(Material material) {
        return RegistryKey.of(RegistryKeys.TRIM_MATERIAL, id(material.name()));
    }

    private static Identifier id(String name) {
        return Identifier.ofVanilla(name.toLowerCase(Locale.ROOT));
    }
}
