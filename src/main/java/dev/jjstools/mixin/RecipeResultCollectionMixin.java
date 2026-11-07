package dev.jjstools.mixin;

import dev.jjstools.modules.ViaRecipeFix;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.recipe.RecipeDisplayEntry;
import net.minecraft.recipe.RecipeFinder;
import net.minecraft.recipe.display.RecipeDisplay;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The recipe book's own list of what can be crafted, for Via Recipe Fix.
 *
 * RecipeDisplayEntryMixin catches the question where it is asked of a recipe, but the book can
 * fill this list without asking it that way, and then its ticks never pass through that hook.
 * So once the book has worked the list out, anything Via Recipe Fix says you cannot really
 * craft is taken back out of it. Only ever removes; nothing is added.
 */
@Mixin(RecipeResultCollection.class)
public abstract class RecipeResultCollectionMixin {
    @Shadow
    @Final
    private List<RecipeDisplayEntry> entries;

    @Shadow
    @Final
    private Set<NetworkRecipeId> craftableRecipes;

    @Inject(method = "populateRecipes", at = @At("TAIL"), require = 0)
    private void jjsTools$vetoCraftable(RecipeFinder finder, Predicate<RecipeDisplay> displayable, CallbackInfo ci) {
        if (craftableRecipes.isEmpty()) return;
        for (RecipeDisplayEntry entry : entries) {
            if (craftableRecipes.contains(entry.id()) && ViaRecipeFix.vetoes(entry)) craftableRecipes.remove(entry.id());
        }
    }
}
