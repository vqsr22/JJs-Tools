package dev.jjstools.mixin;

import dev.jjstools.modules.ViaStonecutterFix;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.StonecuttingRecipe;
import net.minecraft.recipe.display.CuttingRecipeDisplay;
import net.minecraft.screen.StonecutterScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reorders the stonecutter's recipes as soon as the screen works them out for a new input, for
 * Via Stonecutter Fix. Everything else on the screen, the buttons, the scrolling and the click it
 * sends, reads this same list, so all of it follows.
 */
@Mixin(StonecutterScreenHandler.class)
public abstract class StonecutterScreenHandlerMixin {
    @Shadow private CuttingRecipeDisplay.Grouping<StonecuttingRecipe> availableRecipes;

    @Inject(method = "updateInput", at = @At("TAIL"))
    private void jjsTools$serverOrder(ItemStack stack, CallbackInfo ci) {
        availableRecipes = ViaStonecutterFix.order(availableRecipes);
    }
}
