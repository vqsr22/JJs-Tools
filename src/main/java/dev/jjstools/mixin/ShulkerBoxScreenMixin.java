package dev.jjstools.mixin;

import dev.jjstools.mixin.accessor.ScreenTitleAccessor;
import dev.jjstools.modules.StorageGuiTweaks;
import dev.jjstools.tint.BoxColorTracker;
import dev.jjstools.tint.ShulkerScreenTint;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
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
    @Unique private Integer jjsTools$boxRgb;
    @Unique private boolean jjsTools$colorized;

    protected ShulkerBoxScreenMixin(ShulkerBoxScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void jjsTools$onInit(ShulkerBoxScreenHandler handler, PlayerInventory inventory, Text title, CallbackInfo ci) {
        if (!StorageGuiTweaks.on()) return;

        /*
         * Only tint a box you actually opened.
         *
         * Meteor's Better Tooltips previews a box's contents by opening a ShulkerBoxScreen of its
         * own, with nothing in the world behind it. Tinting that from the world would use whichever
         * box you last right-clicked. That screen knows which item it is showing, so it is coloured
         * from that instead, by PeekScreenMixin.
         */
        if (jjsTools$isPreview()) return;

        BoxColorTracker.Detected detected = BoxColorTracker.detect(title.getString());
        if (detected.found()) {
            jjsTools$boxRgb = detected.color() != null
                ? StorageGuiTweaks.colourFor(detected.color())
                : StorageGuiTweaks.undyedColour();
        }

        if (jjsTools$boxRgb != null) jjsTools$colorized = ShulkerScreenTint.prepare(jjsTools$boxRgb);

        Text recoloured = ShulkerScreenTint.title(title, jjsTools$boxRgb);
        if (recoloured != null) ((ScreenTitleAccessor) (Object) this).jjsTools$setTitle(recoloured);
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
        if (ShulkerScreenTint.draw(context, x, y, backgroundWidth, backgroundHeight, jjsTools$boxRgb, jjsTools$colorized)) ci.cancel();
    }
}
