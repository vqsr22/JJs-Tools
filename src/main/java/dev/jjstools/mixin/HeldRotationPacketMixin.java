package dev.jjstools.mixin;

import dev.jjstools.util.HeldRotation;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sends a held facing in your ordinary movement packet, then puts your own view straight back,
 * so the server gets the facing with the movement it belongs to and nothing extra is sent.
 */
@Mixin(ClientPlayerEntity.class)
public abstract class HeldRotationPacketMixin {
    @Unique private boolean jjsTools$held;
    @Unique private float jjsTools$yaw;
    @Unique private float jjsTools$pitch;

    @Inject(method = "sendMovementPackets", at = @At("HEAD"))
    private void jjsTools$holdFacing(CallbackInfo ci) {
        jjsTools$held = HeldRotation.active();
        if (!jjsTools$held) return;

        ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
        jjsTools$yaw = self.getYaw();
        jjsTools$pitch = self.getPitch();
        self.setYaw(HeldRotation.yaw());
        self.setPitch(HeldRotation.pitch());
    }

    @Inject(method = "sendMovementPackets", at = @At("RETURN"))
    private void jjsTools$restoreFacing(CallbackInfo ci) {
        if (!jjsTools$held) return;
        jjsTools$held = false;

        ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
        self.setYaw(jjsTools$yaw);
        self.setPitch(jjsTools$pitch);
    }
}
