package dev.jjstools.mixin.accessor;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.network.packet.Packet;
//#if MC >= 1.21.6
import io.netty.channel.ChannelFutureListener;
//#else
//$$ import net.minecraft.network.PacketCallbacks;
//#endif
import net.minecraft.network.ClientConnection;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ClientConnection.class)
public interface ClientConnectionAccessor {
    @Invoker("sendImmediately")
    //#if MC >= 1.21.6
    void invokeSendImmediately(Packet<?> packet, @Nullable ChannelFutureListener callbacks, boolean flush);
    //#else
    //$$ void invokeSendImmediately(Packet<?> packet, @Nullable PacketCallbacks callbacks, boolean flush);
    //#endif
}
