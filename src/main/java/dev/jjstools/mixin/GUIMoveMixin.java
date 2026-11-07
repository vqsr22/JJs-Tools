package dev.jjstools.mixin;

import dev.jjstools.panel.ControlPanelScreen;
import meteordevelopment.meteorclient.systems.modules.movement.GUIMove;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Meteor's GUI Move lets you walk with the Control Panel open, and it does not know the panel's
 * own text boxes, so typing a W walked you forward. While a box has the keyboard, it stands aside.
 */
@Mixin(value = GUIMove.class, remap = false)
public abstract class GUIMoveMixin {
    @Inject(method = "skip", at = @At("HEAD"), cancellable = true)
    private void jjstools$skipWhileTyping(CallbackInfoReturnable<Boolean> cir) {
        if (MinecraftClient.getInstance().currentScreen instanceof ControlPanelScreen s && s.typing()) cir.setReturnValue(true);
    }
}
