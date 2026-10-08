package dev.jjstools.mixin;

import dev.jjstools.modules.AntiRocketPlace;
import dev.jjstools.tint.BoxColorTracker;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes a rocket ignore the block you are pointing at.
 *
 * Returning PASS rather than cancelling outright is what makes this work: vanilla falls straight
 * through to using the item in the air, which is the boost. Cancelling would eat the click and do
 * nothing at all.
 */
@Mixin(ClientPlayerInteractionManager.class)
public class InteractBlockMixin {
    @Inject(method = "interactBlock", at = @At("HEAD"), cancellable = true)
    private void jjsTools$rocketIgnoresBlocks(ClientPlayerEntity player, Hand hand, BlockHitResult hitResult,
                                              CallbackInfoReturnable<ActionResult> cir) {
        /*
         * Remember the block for Shulker Tint.
         *
         * The server never tells the client what colour an opened shulker is, only its name, so
         * the block you clicked is the only reliable source. Taken from here rather than Fabric
         * API's UseBlockCallback, which would mean adding a dependency for one event.
         */
        BoxColorTracker.onBlockUsed(hitResult.getBlockPos());

        if (AntiRocketPlace.shouldIgnoreBlock()) cir.setReturnValue(ActionResult.PASS);
    }
}
