package dev.jjstools.mixin;

import dev.jjstools.modules.ViaTextureFix;
import net.minecraft.client.item.ItemModelManager;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Renders translated items as what their name says they really are.
 *
 * The stack handed to the model lookup is swapped; the stack itself is untouched everywhere else,
 * which matters because the server still believes you hold a birch sign and the client must agree.
 *
 * ItemModelManager lives in net.minecraft.client.item, not net.minecraft.client.render.item, and
 * has three entry points depending on what is holding the item. All three take the stack.
 */
@Mixin(ItemModelManager.class)
public class ItemModelSwapMixin {
    @ModifyVariable(
        method = {"update", "updateForLivingEntity", "updateForNonLivingEntity"},
        at = @At("HEAD"),
        argsOnly = true
    )
    private ItemStack jjsTools$swapTranslatedItem(ItemStack stack) {
        return ViaTextureFix.displayStack(stack);
    }
}
