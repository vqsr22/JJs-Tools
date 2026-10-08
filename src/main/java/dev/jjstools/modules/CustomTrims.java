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
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.equipment.trim.ArmorTrim;
import net.minecraft.item.equipment.trim.ArmorTrimMaterial;
import net.minecraft.item.equipment.trim.ArmorTrimPattern;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Identifier;

import java.util.List;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.Formatting;
import java.util.Locale;
import java.util.Optional;

/**
 * Client-side armour trims. Shows a chosen trim on your own armour (on your player model and on
 * the armour icons) without changing the real items. Nobody else sees it.
 */
public class CustomTrims extends Module {
    /** How far the custom trim reaches. */
    public enum Scope {
        Worn("Worn only"),
        Carried("Worn and in inventory");

        private final String title;
        Scope(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }

    /** Alphabetical: eighteen patterns in registry order is unreadable in a dropdown. */
    public enum Pattern {
        Bolt, Coast, Dune, Eye, Flow, Host, Raiser, Rib, Sentry,
        Shaper, Silence, Snout, Spire, Tide, Vex, Ward, Wayfinder, Wild
    }
    public enum Material {
        Amethyst, Copper, Diamond, Emerald, Gold, Iron, Lapis, Netherite, Quartz, Redstone, Resin
    }


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


    private final Setting<Boolean> changeTooltip = sgGeneral.add(new BoolSetting.Builder()
        .name("change-trim-tooltip")
        .description("Rewrite the Upgrade lines in the tooltip to match the trim you chose, instead of the one the item really has.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Scope> applyTo = sgGeneral.add(new EnumSetting.Builder<Scope>()
        .name("apply-to")
        .description("Worn only changes the four pieces you have on. Carried as well also recolours already-trimmed armour sitting in your inventory. Untrimmed pieces are always left plain.")
        .defaultValue(Scope.Carried)
        .build()
    );

    private final Piece helmet = new Piece("Helmet", "helmet", EquipmentSlot.HEAD, Pattern.Flow, Material.Gold);
    private final Piece chestplate = new Piece("Chestplate", "chestplate", EquipmentSlot.CHEST, Pattern.Eye, Material.Diamond);
    private final Piece leggings = new Piece("Leggings", "leggings", EquipmentSlot.LEGS, Pattern.Silence, Material.Gold);
    private final Piece boots = new Piece("Boots", "boots", EquipmentSlot.FEET, Pattern.Flow, Material.Gold);

    /*
     * Overrides for the two pieces that get swapped in by other modules.
     *
     * Auto Wear Gold and Auto Turtle Helmet put a different item in a slot you have already set a
     * trim for, and the trim that suited your netherite looks wrong on gold. These take over for
     * that specific item and leave the slot's normal setting alone the rest of the time.
     */
    private final ItemPiece goldenLeggings = new ItemPiece("Golden Leggings", "golden-leggings",
        EquipmentSlot.LEGS, Items.GOLDEN_LEGGINGS, Pattern.Snout, Material.Diamond);

    private final ItemPiece turtleHelmet = new ItemPiece("Turtle Helmet", "turtle-helmet",
        EquipmentSlot.HEAD, Items.TURTLE_HELMET, Pattern.Tide, Material.Gold);

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

    /** A trim that only applies when one specific item is in a slot. */
    private final class ItemPiece {
        private final EquipmentSlot slot;
        private final Item item;
        final Piece piece;

        ItemPiece(String title, String id, EquipmentSlot slot, Item item, Pattern defaultPattern, Material defaultMaterial) {
            this.slot = slot;
            this.item = item;
            this.piece = new Piece(title, id, slot, defaultPattern, defaultMaterial);
        }

        boolean matches(EquipmentSlot other, ItemStack stack) {
            return other == slot && stack.isOf(item) && piece.enabled.get();
        }
    }

    // Used by the mixins

    private static CustomTrims active() {
        Modules modules = Modules.get();
        if (modules == null) return null;
        CustomTrims m = modules.get(CustomTrims.class);
        return m != null && m.isActive() ? m : null;
    }

    /** The override for this exact item if there is one, otherwise the slot's normal piece. */
    private Piece piece(EquipmentSlot slot, ItemStack stack) {
        if (stack != null) {
            if (goldenLeggings.matches(slot, stack)) return goldenLeggings.piece;
            if (turtleHelmet.matches(slot, stack)) return turtleHelmet.piece;
        }
        return piece(slot);
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
        Piece p = m.piece(slot, original);
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
            Piece p = m.piece(slot, stack);
            if (p == null || !p.enabled.get()) return null;
            return materialKey(p.effective());
        }

        return m.applyTo.get() == Scope.Carried ? m.inventoryMaterial(stack) : null;
    }

    /**
     * The trim colour for a piece sitting in your inventory rather than on your body.
     *
     * Only pieces that ALREADY carry a trim are touched. Giving a trim to plain armour would be
     * inventing one, and the point here is recolouring what is there to match your scheme.
     *
     * Which settings apply is worked out from the item's own slot, so a chestplate in your
     * inventory follows the Chestplate group.
     */
    private RegistryKey<ArmorTrimMaterial> inventoryMaterial(ItemStack stack) {
        if (!stack.isIn(ItemTags.TRIMMABLE_ARMOR)) return null;

        /*
         * It has to be YOUR stack.
         *
         * Without this the trim was applied to any armour stack being rendered anywhere, which
         * included other players' gear drawn by nametag mods. Identity, not equality: two players
         * in matching netherite have equal stacks but only one of them is yours.
         */
        if (!isOwnStack(stack)) return null;

        EquipmentSlot slot = slotFor(stack);
        if (slot == null) return null;

        Piece p = piece(slot, stack);
        if (p == null || !p.enabled.get()) return null;

        return materialKey(p.effective());
    }

    /** True only for a stack that is physically in your own inventory, compared by identity. */
    private boolean isOwnStack(ItemStack stack) {
        if (mc.player == null) return false;

        var inventory = mc.player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.getStack(i) == stack) return true;
        }

        // The cursor stack while a container is open is yours too, and is not in the list above.
        return mc.player.currentScreenHandler != null
            && mc.player.currentScreenHandler.getCursorStack() == stack;
    }

    /** The slot a piece of armour belongs in, taken from the item rather than where it is sitting. */
    private EquipmentSlot slotFor(ItemStack stack) {
        var equippable = stack.get(DataComponentTypes.EQUIPPABLE);
        if (equippable == null) return null;

        EquipmentSlot slot = equippable.slot();
        return switch (slot) {
            case HEAD, CHEST, LEGS, FEET -> slot;
            default -> null;
        };
    }

    /**
     * Rewrites the "Eye Armor Trim" and "Gold Material" lines to match the trim being drawn.
     *
     * Vanilla builds those lines from the item's real trim component, which this module never
     * touches, so without this the tooltip contradicts what you are looking at. Matching is done
     * on the translation key rather than the visible text so it works in any language.
     */
    public static void fixTooltip(ItemStack stack, List<Text> lines) {
        CustomTrims m = active();
        if (m == null || !m.changeTooltip.get() || lines.isEmpty()) return;
        RegistryKey<ArmorTrimMaterial> materialKey = iconMaterial(stack);
        if (materialKey == null) return;

        Piece p = m.pieceFor(stack);
        if (p == null) return;

        String patternName = p.pattern.get().name().toLowerCase(Locale.ROOT);
        String materialName = p.effective().name().toLowerCase(Locale.ROOT);

        /*
         * Take the style from the trim material itself rather than forcing a colour.
         *
         * Vanilla colours BOTH lines with the material's own style, which is why a gold trim reads
         * in gold and a diamond one in pale blue. Hardcoding grey, as this did at first, produced
         * the right words in the wrong colour.
         */
        Style style = m.materialStyle(materialKey);

        boolean replacedPattern = false;

        for (int i = 0; i < lines.size(); i++) {
            String key = translationKey(lines.get(i));
            if (key == null) continue;

            if (key.startsWith("trim_pattern.")) {
                lines.set(i, Text.literal(" ").append(
                    Text.translatable("trim_pattern.minecraft." + patternName).setStyle(style)));
                replacedPattern = true;
            }
            else if (key.startsWith("trim_material.")) {
                lines.set(i, Text.literal(" ").append(
                    Text.translatable("trim_material.minecraft." + materialName).setStyle(style)));
            }
        }

        /*
         * Armour that never had a trim has no Upgrade section at all, so there is nothing to
         * rewrite and the lines have to be added. Inserted right after the name, which is where
         * vanilla puts them.
         */
        if (!replacedPattern) {
            int at = Math.min(1, lines.size());

            // Written out rather than translated: the key for this header moved and a missing one
            // renders as raw "item.modifiers.upgrade" text in the tooltip.
            lines.add(at, Text.literal("Upgrade:").formatted(Formatting.GRAY));
            lines.add(at + 1, Text.literal(" ").append(
                Text.translatable("trim_pattern.minecraft." + patternName).setStyle(style)));
            lines.add(at + 2, Text.literal(" ").append(
                Text.translatable("trim_material.minecraft." + materialName).setStyle(style)));
        }
    }

    /** The colour vanilla would use for this trim material, read off the material itself. */
    private Style materialStyle(RegistryKey<ArmorTrimMaterial> key) {
        if (mc.world == null) return Style.EMPTY.withColor(Formatting.GRAY);

        try {
            Optional<RegistryEntry.Reference<ArmorTrimMaterial>> entry = mc.world.getRegistryManager()
                .getOrThrow(RegistryKeys.TRIM_MATERIAL).getOptional(key);

            if (entry.isPresent()) {
                Style style = entry.get().value().description().getStyle();
                if (style != null && style.getColor() != null) return style;
            }
        } catch (Throwable ignored) {
            // Falls through to grey, which is what vanilla uses when it cannot resolve one.
        }
        return Style.EMPTY.withColor(Formatting.GRAY);
    }

    private static String translationKey(Text text) {
        if (text.getContent() instanceof TranslatableTextContent translatable) return translatable.getKey();

        for (Text sibling : text.getSiblings()) {
            String key = translationKey(sibling);
            if (key != null) return key;
        }
        return null;
    }

    /** The settings that apply to this stack, worn or carried. */
    private Piece pieceFor(ItemStack stack) {
        if (mc.player != null) {
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                if (mc.player.getEquippedStack(slot) == stack) return piece(slot, stack);
            }
        }

        if (applyTo.get() != Scope.Carried) return null;

        EquipmentSlot slot = slotFor(stack);
        return slot == null ? null : piece(slot, stack);
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
