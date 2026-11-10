package dev.jjstools.tint;

import dev.jjstools.modules.StorageGuiTweaks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

/**
 * Colouring a shulker box screen: its panel and its title.
 *
 * Shared by the real screen and Meteor's peek screen, so a box looks the same whether you opened
 * it in the world or middle-clicked it in your inventory.
 */
public final class ShulkerScreenTint {
    /** The identifier vanilla uses, so whatever your resource pack provides is what gets drawn. */
    public static final Identifier TEXTURE = Identifier.ofVanilla("textures/gui/container/shulker_box.png");

    private ShulkerScreenTint() {}

    /** The colour for a shulker box item, from its dye, or null if it is not a shulker box. */
    public static @Nullable Integer colourOf(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof BlockItem item)) return null;
        if (!(item.getBlock() instanceof ShulkerBoxBlock shulker)) return null;

        DyeColor dye = shulker.getColor();
        return dye != null ? StorageGuiTweaks.colourFor(dye) : StorageGuiTweaks.undyedColour();
    }

    /** Builds the recoloured texture if Colourise is in use. True if it is ready to draw from. */
    public static boolean prepare(int rgb) {
        if (!StorageGuiTweaks.backgroundTinted() || !StorageGuiTweaks.colorizing()) return false;
        return TintedTexture.build(TEXTURE, rgb, StorageGuiTweaks.strength() / 100.0, StorageGuiTweaks.splitY());
    }

    /** The title recoloured as the title settings ask, or null to leave it as it is. */
    public static @Nullable Text title(Text title, @Nullable Integer rgb) {
        Integer titleRgb = switch (StorageGuiTweaks.titleMode()) {
            case Vanilla -> null;
            case MatchBox -> rgb == null ? null : Colors.adjustBrightness(rgb, StorageGuiTweaks.titleBrightness() / 100.0);
            case InverseBrightness -> rgb == null ? null : Colors.inverseLightness(rgb, StorageGuiTweaks.inverseStrength() / 100.0);
            case Custom -> StorageGuiTweaks.customTitleColour();
        };
        if (titleRgb == null) return null;

        // Formatting.strip also removes raw section codes some names carry inside their text.
        Text base = StorageGuiTweaks.overrideNameColours()
            ? Text.literal(Formatting.strip(title.getString()))
            : title.copy();
        return base.copy().withColor(titleRgb);
    }

    /**
     * Draws the panel: the box's half tinted down to the tint height, the inventory half as the
     * pack has it. Returns false if there is nothing to tint, to let the screen draw its own.
     */
    public static boolean draw(DrawContext context, int x, int y, int width, int height, @Nullable Integer rgb, boolean colorized) {
        if (!StorageGuiTweaks.on() || !StorageGuiTweaks.backgroundTinted() || rgb == null) return false;

        int split = Math.max(0, Math.min(StorageGuiTweaks.splitY(), height));

        if (split > 0) {
            if (colorized) {
                context.drawTexture(dev.jjstools.compat.Gui.TEXTURED, TintedTexture.ID, x, y,
                    0.0F, 0.0F, width, split, 256, 256);
            }
            else {
                int tint = Colors.multiplyTint(rgb, StorageGuiTweaks.strength() / 100.0);
                context.drawTexture(dev.jjstools.compat.Gui.TEXTURED, TEXTURE, x, y,
                    0.0F, 0.0F, width, split, 256, 256, tint);
            }
        }

        if (split < height) {
            context.drawTexture(dev.jjstools.compat.Gui.TEXTURED, TEXTURE, x, y + split,
                0.0F, (float) split, width, height - split, 256, 256);
        }

        return true;
    }
}
