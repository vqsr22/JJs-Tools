package dev.jjstools.mixin;

import dev.jjstools.modules.ArmorSwapModule;
import net.minecraft.entity.EquipmentSlot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives Auto Turtle Helmet and Auto Trousers priority over Meteor's Auto Armor. Auto Armor already
 * treats a piece scored Integer.MAX_VALUE as "leave it alone" (it does this for elytras and
 * Curse of Binding), so while one of our modules needs the slot we give it that score.
 */
@Mixin(targets = "meteordevelopment.meteorclient.systems.modules.combat.AutoArmor$ArmorPiece", remap = false)
public abstract class AutoArmorMixin {
    @Shadow @Final private EquipmentSlot slot;
    @Shadow private int score;

    @Inject(method = "calculate", at = @At("TAIL"))
    private void jjstools$yieldToArmorSwap(CallbackInfo ci) {
        if (ArmorSwapModule.claims(slot)) score = Integer.MAX_VALUE;
    }
}
