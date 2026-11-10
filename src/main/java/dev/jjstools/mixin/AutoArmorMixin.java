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
    //#if MC >= 1.21.5
    @Shadow @Final private EquipmentSlot slot;
    //#else
    //$$ // Meteor's 1.21.4 build numbers its armour pieces from the feet up instead.
    //$$ @Shadow @Final private int id;
    //#endif
    @Shadow private int score;

    @Inject(method = "calculate", at = @At("TAIL"))
    private void jjstools$yieldToArmorSwap(CallbackInfo ci) {
        //#if MC >= 1.21.5
        if (ArmorSwapModule.claims(slot)) score = Integer.MAX_VALUE;
        //#else
        //$$ EquipmentSlot slot = switch (id) {
        //$$     case 0 -> EquipmentSlot.FEET;
        //$$     case 1 -> EquipmentSlot.LEGS;
        //$$     case 2 -> EquipmentSlot.CHEST;
        //$$     default -> EquipmentSlot.HEAD;
        //$$ };
        //$$ if (ArmorSwapModule.claims(slot)) score = Integer.MAX_VALUE;
        //#endif
    }
}
