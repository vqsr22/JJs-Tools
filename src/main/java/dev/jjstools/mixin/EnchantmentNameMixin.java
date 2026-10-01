package dev.jjstools.mixin;

import dev.jjstools.modules.EnchantFix;
import dev.jjstools.util.VanillaEnchants;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Recolours enchantment names by ID instead of the (scrambled) #minecraft:curse tag. */
@Mixin(Enchantment.class)
public abstract class EnchantmentNameMixin {
    @Inject(method = "getName", at = @At("RETURN"))
    private static void jjstools$fixCurseColour(RegistryEntry<Enchantment> enchantment, int level,
                                                CallbackInfoReturnable<Text> cir) {
        if (!EnchantFix.shouldFixColours()) return;
        VanillaEnchants.fixColour(enchantment, cir.getReturnValue());
    }
}
