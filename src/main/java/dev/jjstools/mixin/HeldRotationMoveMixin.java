package dev.jjstools.mixin;

import dev.jjstools.util.HeldRotation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Works out your movement and jumps facing the held way, so they match the facing the server is
 * sent and it has nothing to pull you back for. Only for you, and only while a facing is held.
 */
@Mixin(LivingEntity.class)
public abstract class HeldRotationMoveMixin {
    @Unique private boolean jjsTools$travelHeld;
    @Unique private float jjsTools$travelYaw;
    @Unique private float jjsTools$travelPitch;
    @Unique private boolean jjsTools$jumpHeld;
    @Unique private float jjsTools$jumpYaw;
    @Unique private float jjsTools$jumpPitch;

    @Unique
    private boolean jjsTools$isYou() {
        return HeldRotation.active() && (Object) this == MinecraftClient.getInstance().player;
    }

    @Inject(method = "travel", at = @At("HEAD"))
    private void jjsTools$travelFacing(Vec3d movementInput, CallbackInfo ci) {
        jjsTools$travelHeld = jjsTools$isYou();
        if (!jjsTools$travelHeld) return;

        LivingEntity self = (LivingEntity) (Object) this;
        jjsTools$travelYaw = self.getYaw();
        jjsTools$travelPitch = self.getPitch();
        self.setYaw(HeldRotation.yaw());
        self.setPitch(HeldRotation.pitch());
    }

    @Inject(method = "travel", at = @At("RETURN"))
    private void jjsTools$travelRestore(Vec3d movementInput, CallbackInfo ci) {
        if (!jjsTools$travelHeld) return;
        jjsTools$travelHeld = false;

        LivingEntity self = (LivingEntity) (Object) this;
        self.setYaw(jjsTools$travelYaw);
        self.setPitch(jjsTools$travelPitch);
    }

    @Inject(method = "jump", at = @At("HEAD"))
    private void jjsTools$jumpFacing(CallbackInfo ci) {
        jjsTools$jumpHeld = jjsTools$isYou();
        if (!jjsTools$jumpHeld) return;

        LivingEntity self = (LivingEntity) (Object) this;
        jjsTools$jumpYaw = self.getYaw();
        jjsTools$jumpPitch = self.getPitch();
        self.setYaw(HeldRotation.yaw());
        self.setPitch(HeldRotation.pitch());
    }

    @Inject(method = "jump", at = @At("RETURN"))
    private void jjsTools$jumpRestore(CallbackInfo ci) {
        if (!jjsTools$jumpHeld) return;
        jjsTools$jumpHeld = false;

        LivingEntity self = (LivingEntity) (Object) this;
        self.setYaw(jjsTools$jumpYaw);
        self.setPitch(jjsTools$jumpPitch);
    }
}
