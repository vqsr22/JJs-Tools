package dev.jjstools.mixin;

import dev.jjstools.util.Borderless;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Goes borderless at startup, once the window exists, if it was left switched on. */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientBorderlessMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void jjsTools$borderlessAtStartup(CallbackInfo ci) {
        Borderless.onStartup();
    }
}
