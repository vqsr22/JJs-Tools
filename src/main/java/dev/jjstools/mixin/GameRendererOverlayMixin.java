package dev.jjstools.mixin;

import dev.jjstools.util.ScreenOverlay;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Right after Minecraft draws the GUI, every frame: see ScreenOverlay. Before Meteor draws its own screens there. */
@Mixin(value = GameRenderer.class, priority = 900)
public abstract class GameRendererOverlayMixin {
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V", shift = At.Shift.AFTER), require = 0)
    private void jjsTools$overlay(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        ScreenOverlay.draw();
    }
}
