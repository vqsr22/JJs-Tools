package dev.jjstools.mixin;

import dev.jjstools.util.DisconnectReason;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.DisconnectionInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Notes how a connection ends, for DisconnectReason. Only notes; changes nothing. */
@Mixin(value = ClientConnection.class, priority = 500)
public abstract class ClientConnectionDisconnectMixin {
    @Inject(method = "exceptionCaught", at = @At("HEAD"), require = 0)
    private void jjsTools$error(ChannelHandlerContext context, Throwable throwable, CallbackInfo ci) {
        DisconnectReason.onError(throwable);
    }

    @Inject(method = "channelInactive", at = @At("HEAD"), require = 0)
    private void jjsTools$closed(ChannelHandlerContext context, CallbackInfo ci) {
        DisconnectReason.onClosed();
    }

    @Inject(method = "disconnect(Lnet/minecraft/network/DisconnectionInfo;)V", at = @At("HEAD"), require = 0)
    private void jjsTools$disconnect(DisconnectionInfo info, CallbackInfo ci) {
        DisconnectReason.onDisconnect();
    }
}
