package dev.jjstools.mixin;

import dev.jjstools.modules.ViaRecipeFix;
import net.minecraft.recipe.RecipeDisplayEntry;
import net.minecraft.recipe.RecipeFinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Where the recipe book asks whether a recipe can be crafted, for Via Recipe Fix.
 *
 * Checked on the way out, after vanilla has given its answer, and only a yes is ever turned into
 * a no. Every book screen goes through this one method, the inventory's two by two grid and the
 * crafting table alike.
 */
@Mixin(RecipeDisplayEntry.class)
public abstract class RecipeDisplayEntryMixin {
    @Inject(method = "isCraftable", at = @At("RETURN"), cancellable = true)
    private void jjsTools$checkCraftable(RecipeFinder finder, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && ViaRecipeFix.vetoes((RecipeDisplayEntry) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
