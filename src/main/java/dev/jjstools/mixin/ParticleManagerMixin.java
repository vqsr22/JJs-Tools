package dev.jjstools.mixin;

import dev.jjstools.modules.BetterHitreg;
import dev.jjstools.modules.Unobtrusive;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.particle.ParticleEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Drops the server's copy of the hit particles Better Hitreg has already thrown.
 *
 * Caught at the point a particle is built rather than where it is asked for, because the crit
 * and sharpness bursts arrive as an entity event rather than as particle packets, and this is
 * the one place both paths pass through.
 */
@Mixin(ParticleManager.class)
public class ParticleManagerMixin {
    @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
    private void jjsTools$swallowEchoParticles(ParticleEffect effect, double x, double y, double z,
                                               double velocityX, double velocityY, double velocityZ,
                                               CallbackInfoReturnable<Particle> cir) {
        if (BetterHitreg.swallowParticle(effect, x, y, z)) cir.setReturnValue(null);
    }

    /**
     * Firework sparks and your own potion swirls, for Unobtrusive.
     *
     * The same choke point, for the same reason: rocket trails, firework bursts and potion
     * swirls all arrive by different routes, and this is where every one of them becomes a
     * particle.
     */
    @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
    private void jjsTools$unobtrusiveParticles(ParticleEffect effect, double x, double y, double z,
                                               double velocityX, double velocityY, double velocityZ,
                                               CallbackInfoReturnable<Particle> cir) {
        if (Unobtrusive.hideParticle(effect, x, y, z)) cir.setReturnValue(null);
    }
}
