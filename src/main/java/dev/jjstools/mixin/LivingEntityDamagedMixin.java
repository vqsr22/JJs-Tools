package dev.jjstools.mixin;

import dev.jjstools.modules.BetterHitreg;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops the server's copy of a hit Better Hitreg has already played.
 *
 * Without this you get everything twice, once on the click and once a round trip later, which
 * is worse than waiting. The flinch is still restarted from here rather than skipped outright,
 * so the server stays the authority on how long the target stays red and a hit that was
 * predicted wrongly still looks right.
 *
 * Narrow on purpose: only a hit whose attacker is you, and only one this module predicted in
 * the last second. Anything else reaching the entity goes through untouched.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDamagedMixin {
    @Inject(method = "onDamaged", at = @At("HEAD"), cancellable = true)
    private void jjsTools$swallowEcho(DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;

        if (!BetterHitreg.alreadyShown(self, source.getAttacker())) return;

        self.maxHurtTime = 10;
        self.hurtTime = 10;
        ci.cancel();
    }
}
