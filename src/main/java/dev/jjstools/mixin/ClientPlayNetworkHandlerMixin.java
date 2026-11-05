package dev.jjstools.mixin;

import dev.jjstools.modules.ChatNotifications;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells Chat Notifier about commands you send (without the slash), so it can skip your own whispers. */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Inject(method = "sendChatCommand", at = @At("HEAD"))
    private void jjstools$commandSent(String command, CallbackInfo ci) {
        ChatNotifications.onCommandSent(command);
    }
}
