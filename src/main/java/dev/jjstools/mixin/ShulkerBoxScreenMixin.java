package dev.jjstools.mixin;

import dev.jjstools.mixin.accessor.ScreenTitleAccessor;
import dev.jjstools.modules.ShulkerTint;
import dev.jjstools.tint.BoxColorTracker;
import dev.jjstools.tint.Colors;
import dev.jjstools.tint.TintedTexture;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tints the shulker GUI and its title to the box's colour.
 *
 * Carried over from the Shulker Tint mod unchanged in behaviour; only the settings have moved from
 * its own config file into the module.
 */
@Mixin(ShulkerBoxScreen.class)
public abstract class ShulkerBoxScreenMixin extends HandledScreen<ShulkerBoxScreenHandler> {
    // The identifier vanilla uses, so whatever your resource pack provides is what gets drawn.
    @Shadow @Final private static Identifier TEXTURE;

    @Unique private Integer jjsTools$boxRgb;
    @Unique private boolean jjsTools$colorized;

    protected ShulkerBoxScreenMixin(ShulkerBoxScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void jjsTools$onInit(ShulkerBoxScreenHandler handler, PlayerInventory inventory, Text title, CallbackInfo ci) {
        if (!ShulkerTint.on()) return;

        /*
         * Only tint a box you actually opened.
         *
         * Meteor's Better Tooltips previews a box's contents by opening a ShulkerBoxScreen of its
         * own, with nothing in the world behind it. Tinting that would use whichever box you last
         * right-clicked, which is worse than not tinting at all. A click that is too old to trust
         * reports nothing, so the preview is left vanilla.
         */
        if (jjsTools$isPreview()) return;

        BoxColorTracker.Detected detected = BoxColorTracker.detect(title.getString());
        if (detected.found()) {
            jjsTools$boxRgb = detected.color() != null
                ? ShulkerTint.colourFor(detected.color())
                : ShulkerTint.undyedColour();
        }

        if (jjsTools$boxRgb != null && ShulkerTint.backgroundTinted() && ShulkerTint.colorizing()) {
            jjsTools$colorized = TintedTexture.build(TEXTURE, jjsTools$boxRgb,
                ShulkerTint.strength() / 100.0, ShulkerTint.splitY());
        }

        Integer titleRgb = switch (ShulkerTint.titleMode()) {
            case Vanilla -> null;
            case MatchBox -> jjsTools$boxRgb == null ? null
                : Colors.adjustBrightness(jjsTools$boxRgb, ShulkerTint.titleBrightness() / 100.0);
            case InverseBrightness -> jjsTools$boxRgb == null ? null
                : Colors.inverseLightness(jjsTools$boxRgb, ShulkerTint.inverseStrength() / 100.0);
            case Custom -> ShulkerTint.customTitleColour();
        };

        if (titleRgb != null) {
            // Formatting.strip also removes raw section codes some names carry inside their text.
            Text base = ShulkerTint.overrideNameColours()
                ? Text.literal(Formatting.strip(title.getString()))
                : title.copy();

            ((ScreenTitleAccessor) (Object) this).jjsTools$setTitle(base.copy().withColor(titleRgb));
        }
    }

    /**
     * True when this screen is a preview rather than a box you opened in the world.
     *
     * At construction time the screen has not been swapped in yet, so currentScreen is still
     * whatever you were looking at. Opening a real box happens from the world, with no screen up;
     * a preview is opened from your inventory, with one. That difference is the test.
     *
     * This matters because of the crosshair fallback in the colour tracker: previewing a box from
     * your inventory while stood in front of a different one would otherwise tint the preview with
     * the wrong box's colour.
     */
    @Unique
    private boolean jjsTools$isPreview() {
        // The screen's own client field is not set until init, so it is null here. Asking the game
        // directly is the only thing that works at construction time.
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        return mc != null && mc.currentScreen instanceof HandledScreen<?>;
    }

    @Inject(method = "drawBackground", at = @At("HEAD"), cancellable = true)
    private void jjsTools$drawTinted(DrawContext context, float deltaTicks, int mouseX, int mouseY, CallbackInfo ci) {
        if (!ShulkerTint.on() || !ShulkerTint.backgroundTinted() || jjsTools$boxRgb == null) return;

        int split = Math.max(0, Math.min(ShulkerTint.splitY(), backgroundHeight));

        // Container half: tinted.
        if (split > 0) {
            if (jjsTools$colorized) {
                context.drawTexture(RenderPipelines.GUI_TEXTURED, TintedTexture.ID, x, y,
                    0.0F, 0.0F, backgroundWidth, split, 256, 256);
            }
            else {
                int tint = Colors.multiplyTint(jjsTools$boxRgb, ShulkerTint.strength() / 100.0);
                context.drawTexture(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y,
                    0.0F, 0.0F, backgroundWidth, split, 256, 256, tint);
            }
        }

        // Player inventory half: untouched pack texture.
        if (split < backgroundHeight) {
            context.drawTexture(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y + split,
                0.0F, (float) split, backgroundWidth, backgroundHeight - split, 256, 256);
        }

        ci.cancel();
    }
}
