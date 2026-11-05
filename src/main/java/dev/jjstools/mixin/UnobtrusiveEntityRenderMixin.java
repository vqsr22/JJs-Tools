package dev.jjstools.mixin;

import dev.jjstools.modules.Unobtrusive;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Thrown items at the camera, for Unobtrusive.
 *
 * This is the question the renderer asks of every entity before drawing it, and it is handed
 * the camera position along with it, which is exactly what "too close to the camera" needs.
 * Answering no here means the item is simply not drawn that frame; nothing about it changes.
 */
@Mixin(EntityRenderManager.class)
public abstract class UnobtrusiveEntityRenderMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void jjsTools$hideCloseThrown(Entity entity, Frustum frustum, double x, double y, double z,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (Unobtrusive.hideThrown(entity, x, y, z)) cir.setReturnValue(false);
    }
}
