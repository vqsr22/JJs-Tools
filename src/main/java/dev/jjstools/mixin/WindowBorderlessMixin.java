package dev.jjstools.mixin;

import dev.jjstools.util.Borderless;
import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Comes back to borderless after leaving real fullscreen, when borderless is switched on. */
@Mixin(Window.class)
public abstract class WindowBorderlessMixin {
    @Inject(method = "toggleFullscreen", at = @At("TAIL"))
    private void jjsTools$backToBorderless(CallbackInfo ci) {
        Borderless.afterFullscreenToggle((Window) (Object) this);
    }
}
