package dev.jjstools.mixin;

import dev.jjstools.modules.SmootherFreecam;
import net.minecraft.client.render.Camera;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Smoother Freecam's drone mode: once the camera is placed for the frame, it is tilted and
 * flipped on top of where it looks. The world is drawn from this rotation, and the camera's
 * directions (used for sounds and particles facing you) are worked out again from it.
 */
@Mixin(Camera.class)
public abstract class CameraDroneMixin {
    @Shadow @Final private Quaternionf rotation;
    @Shadow @Final private Vector3f horizontalPlane;
    @Shadow @Final private Vector3f verticalPlane;
    @Shadow @Final private Vector3f diagonalPlane;

    @Inject(method = "update", at = @At("TAIL"), require = 0)
    private void jjsTools$drone(CallbackInfo ci) {
        Quaternionf extra = SmootherFreecam.droneRotation();
        if (extra == null) return;
        rotation.mul(extra);
        horizontalPlane.set(0, 0, -1).rotate(rotation);
        verticalPlane.set(0, 1, 0).rotate(rotation);
        diagonalPlane.set(-1, 0, 0).rotate(rotation);
    }
}
