package dev.jjstools.mixin;

import dev.jjstools.modules.Unobtrusive;
import net.minecraft.client.gui.hud.InGameOverlayRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves the burning overlay, for Unobtrusive.
 *
 * Shifted on the way in and shifted back on the way out, rather than by reaching inside for the
 * offset vanilla uses. That way it does not matter how the flames are drawn in between: the
 * method pushes and pops its own matrices for each flame, so whatever is on top when it returns
 * is exactly what was on top when it started, and putting the shift back leaves nothing behind.
 */
@Mixin(InGameOverlayRenderer.class)
public abstract class UnobtrusiveFireMixin {
    /** The shift actually applied on the way in, so the way out undoes that and not a new value. */
    @Unique
    private static float jjsTools$fireShift;

    @Inject(method = "renderFireOverlay", at = @At("HEAD"))
    //#if MC >= 1.21.9
    private static void jjsTools$moveFire(MatrixStack matrices, VertexConsumerProvider vertexConsumers, Sprite sprite,
                                          CallbackInfo ci) {
    //#else
    //$$ private static void jjsTools$moveFire(MatrixStack matrices, VertexConsumerProvider vertexConsumers, CallbackInfo ci) {
    //#endif
        jjsTools$fireShift = Unobtrusive.fireOffset();
        if (jjsTools$fireShift != 0.0F) matrices.translate(0.0F, jjsTools$fireShift, 0.0F);
    }

    @Inject(method = "renderFireOverlay", at = @At("RETURN"))
    //#if MC >= 1.21.9
    private static void jjsTools$restoreFire(MatrixStack matrices, VertexConsumerProvider vertexConsumers, Sprite sprite,
                                             CallbackInfo ci) {
    //#else
    //$$ private static void jjsTools$restoreFire(MatrixStack matrices, VertexConsumerProvider vertexConsumers, CallbackInfo ci) {
    //#endif
        if (jjsTools$fireShift != 0.0F) matrices.translate(0.0F, -jjsTools$fireShift, 0.0F);
        jjsTools$fireShift = 0.0F;
    }
}
