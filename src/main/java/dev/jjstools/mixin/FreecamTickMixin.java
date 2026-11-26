package dev.jjstools.mixin;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.render.Freecam;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips Meteor Freecam's tick while the game has no camera entity, as for a moment while a server
 * moves you to another world (2b2t's queue to the main server). Its tick uses the camera entity
 * without checking for one, and with Freecam kept on through the move it crashed the game.
 */
@Mixin(value = Freecam.class, remap = false)
public abstract class FreecamTickMixin {
    @Inject(method = "onTick", at = @At("HEAD"), cancellable = true, require = 0)
    private void jjstools$skipWithoutCamera(TickEvent.Post event, CallbackInfo ci) {
        if (MinecraftClient.getInstance().getCameraEntity() == null) ci.cancel();
    }
}
