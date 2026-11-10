package dev.jjstools.mixin;

import dev.jjstools.modules.StorageGuiTweaks;
import dev.jjstools.util.FittedText;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Shrinks a storage container's name when it is too long for the GUI: shulker boxes, and the
 * chest-style screens of chests, barrels and ender chests.
 *
 * Vanilla draws the title at a fixed size and lets a long name run out past the right edge of the
 * panel, which a renamed box full of a long item's name does constantly.
 *
 * Three ways to fit it, and they differ in what they are willing to give up. Spaces holds the
 * letters to a size where every font pixel is a whole screen pixel square and takes the shortfall
 * out of the gaps between words, which for a name only a little too long means no shrinking at
 * all. Aliasing keeps to those same sizes and changes nothing else, so the line can fall short of
 * the width. Scale takes exactly the width it was given and accepts that some pixels come out
 * longer than they are wide. All three use one number for both axes, so a letter is never
 * squashed on one side only.
 *
 * Vanilla is left to do the actual writing in both, in vanilla's colour and with vanilla's
 * shadow. That matters because the title has usually been recoloured by Storage GUI Tweaks already, and
 * because the colour vanilla passes here is not ours to guess at.
 *
 * Every drawText call in drawForeground comes through here, and the title is picked out by
 * identity rather than by which one is drawn first, so the player inventory label is left alone
 * even if vanilla ever reorders the two.
 */
@Mixin(HandledScreen.class)
public abstract class HandledScreenTitleMixin {
    @Shadow @Final protected ScreenHandler handler;

    //#if MC >= 1.21.6
    @Redirect(
        method = "drawForeground",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;IIIZ)V"
        ),
        require = 0
    )
    private void jjsTools$fitTitle(DrawContext context, TextRenderer textRenderer, Text text,
                                   int x, int y, int colour, boolean shadow) {
        jjsTools$drawTitle(context, textRenderer, text, x, y, colour, shadow);
    }
    //#else
    //$$ // Before 1.21.6 drawText gives back where the text ends.
    //$$ @Redirect(
    //$$     method = "drawForeground",
    //$$     at = @At(
    //$$         value = "INVOKE",
    //$$         target = "Lnet/minecraft/client/gui/DrawContext;drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;IIIZ)I"
    //$$     ),
    //$$     require = 0
    //$$ )
    //$$ private int jjsTools$fitTitle(DrawContext context, TextRenderer textRenderer, Text text,
    //$$                               int x, int y, int colour, boolean shadow) {
    //$$     jjsTools$drawTitle(context, textRenderer, text, x, y, colour, shadow);
    //$$     return x + textRenderer.getWidth(text);
    //$$ }
    //#endif

    private void jjsTools$drawTitle(DrawContext context, TextRenderer textRenderer, Text text,
                                    int x, int y, int colour, boolean shadow) {
        boolean storage = (Object) this instanceof ShulkerBoxScreen
            || (Object) this instanceof GenericContainerScreen && StorageGuiTweaks.fitsChestTitles();

        if (storage && text == ((Screen) (Object) this).getTitle()) {
            int limit = StorageGuiTweaks.titleLimit(handler, x);
            int width = textRenderer.getWidth(text);

            if (limit > 0 && width > limit) {
                StorageGuiTweaks.TitleFit fit = StorageGuiTweaks.titleFit();

                if (fit != StorageGuiTweaks.TitleFit.Scale) {
                    boolean flex = fit == StorageGuiTweaks.TitleFit.Spaces;

                    if (FittedText.draw(context, textRenderer, text, x, y, limit, colour, shadow, flex)) return;

                    // The spaces could not take it. A smaller, undistorted line still beats a
                    // distorted one, so that is tried before giving up on the pixel grid.
                    if (flex && FittedText.draw(context, textRenderer, text, x, y, limit, colour, shadow, false)) return;
                }

                jjsTools$squeeze(context, textRenderer, text, x, y, colour, shadow, limit / (float) width);
                return;
            }
        }

        context.drawText(textRenderer, text, x, y, colour, shadow);
    }

    /**
     * The whole line scaled down as one.
     *
     * Also the fallback when the aliased path declines: at a GUI scale of 1 there is no size
     * below full size that lands on whole pixels, so there is nothing for it to do.
     */
    private void jjsTools$squeeze(DrawContext context, TextRenderer textRenderer, Text text,
                                  int x, int y, int colour, boolean shadow, float scale) {
        /*
         * Shrunk from the left edge and about the middle of the line.
         *
         * Scaling about the top-left would leave a short name floating above where the title
         * normally sits, since the text loses height off the bottom. Half the height it loses is
         * given back as a nudge down, which keeps it on the same line as before.
         */
        float drop = textRenderer.fontHeight * (1.0F - scale) * 0.5F;

        dev.jjstools.compat.Gui.push(context);
        dev.jjstools.compat.Gui.translate(context, x, y + drop);
        dev.jjstools.compat.Gui.scale(context, scale, scale);
        context.drawText(textRenderer, text, 0, 0, colour, shadow);
        dev.jjstools.compat.Gui.pop(context);
    }
}
