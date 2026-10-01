package dev.jjstools.mixin.accessor;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.network.packet.Packet;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.ClientConnection;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ClientConnection.class)
public interface ClientConnectionAccessor {
    @Invoker("sendImmediately")
    void invokeSendImmediately(Packet<?> packet, @Nullable ChannelFutureListener callbacks, boolean flush);
}
