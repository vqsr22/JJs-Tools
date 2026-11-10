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
    //#if MC >= 1.21.6
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V", shift = At.Shift.AFTER), require = 0)
    //#else
    //$$ // Before 1.21.6 the HUD is drawn straight away and flushed with DrawContext.draw.
    //$$ @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;draw()V", ordinal = 0, shift = At.Shift.AFTER), require = 0)
    //#endif
    private void jjsTools$overlay(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        ScreenOverlay.draw();
    }
}
