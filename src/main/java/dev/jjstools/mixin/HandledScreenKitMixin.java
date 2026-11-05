package dev.jjstools.mixin;

import dev.jjstools.modules.KitCreator;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws Kit Creator's Scan and Create buttons on the shulker box screen.
 *
 * Hooked on HandledScreen rather than ShulkerBoxScreen because ShulkerBoxScreen does not override
 * render; the screen type is checked here instead. Drawn at the end of render, in screen
 * coordinates, so it does not depend on whether the matrix is still translated to the window's
 * corner at that point.
 *
 * The buttons are not vanilla widgets, so the click is picked up by the module through Meteor's
 * mouse event and cancelled there. That keeps the whole thing out of the screen's own click
 * handling, which drops whatever you are holding when you click outside the window.
 */
@Mixin(HandledScreen.class)
public abstract class HandledScreenKitMixin {
    @Shadow protected int x;
    @Shadow protected int y;
    @Shadow protected int backgroundWidth;
    @Shadow protected int backgroundHeight;

    @Inject(method = "render", at = @At("TAIL"))
    private void jjsTools$kitButtons(DrawContext context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        if (!((Object) this instanceof ShulkerBoxScreen)) return;
        if (!KitCreator.on()) return;

        KitCreator.drawButtons(context, x, y, backgroundWidth, backgroundHeight, mouseX, mouseY);
    }
}
