package dev.jjstools.mixin.accessor;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.sound.SoundEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The sound an entity makes when it is hurt.
 *
 * Protected on LivingEntity, and every mob overrides it, so asking the entity is the only way
 * to get the right one. Better Hitreg needs it to play the hurt sound before the server sends
 * the damage event that would normally trigger it.
 */
@Mixin(LivingEntity.class)
public interface HurtSoundInvoker {
    @Invoker("getHurtSound")
    SoundEvent jjsTools$getHurtSound(DamageSource source);
}
