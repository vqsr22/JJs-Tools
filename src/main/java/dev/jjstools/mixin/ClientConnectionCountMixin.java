package dev.jjstools.mixin;

import dev.jjstools.util.PacketLimits;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts every packet as it actually goes out, for PacketLimits. Meteor's send events miss the
 * packets modules send "silently" (often inventory clicks resent that way), but everything that
 * leaves passes through here, so the counts match what the server sees.
 */
@Mixin(ClientConnection.class)
public abstract class ClientConnectionCountMixin {
    @Inject(method = "sendImmediately", at = @At("HEAD"), require = 0)
    //#if MC >= 1.21.8
    private void jjsTools$count(Packet<?> packet, io.netty.channel.ChannelFutureListener callbacks, boolean flush, CallbackInfo ci) {
    //#else
    //$$ private void jjsTools$count(Packet<?> packet, net.minecraft.network.PacketCallbacks callbacks, boolean flush, CallbackInfo ci) {
    //#endif
        PacketLimits.onSent(packet);
    }
}
