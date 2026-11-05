package dev.jjstools.mixin;

import dev.jjstools.util.HeldRotation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Swaps the movement keys while a facing is held for the ones that keep you going the way you
 * meant, facing the held way. The server is told which keys are down, so these have to be real
 * key combinations, not just a direction.
 */
@Mixin(KeyboardInput.class)
public abstract class HeldRotationInputMixin extends Input {
    @Inject(method = "tick", at = @At("TAIL"))
    private void jjsTools$rewriteKeys(CallbackInfo ci) {
        if (!HeldRotation.active() || MinecraftClient.getInstance().player == null) return;

        Vec2f keys = HeldRotation.rewrite(this.getMovementInput(), MinecraftClient.getInstance().player.getYaw());
        if (keys == null) return;

        float length = (float) Math.sqrt(keys.x * keys.x + keys.y * keys.y);
        this.movementVector = length > 1.0F ? new Vec2f(keys.x / length, keys.y / length) : keys;
        this.playerInput = new PlayerInput(
            keys.y > 0, keys.y < 0, keys.x > 0, keys.x < 0,
            this.playerInput.jump(), this.playerInput.sneak(), this.playerInput.sprint());
    }
}
