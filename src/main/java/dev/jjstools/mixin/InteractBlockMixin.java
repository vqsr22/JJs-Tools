package dev.jjstools.mixin;

import dev.jjstools.modules.AntiRocketPlace;
import dev.jjstools.modules.FastRename;
import dev.jjstools.modules.StorageGuiTweaks;
import dev.jjstools.modules.SignToItemFrame;
import dev.jjstools.tint.BoxColorTracker;
import dev.jjstools.tint.StorageMemory;
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
         * Remember the block for Storage GUI Tweaks.
         *
         * The server never tells the client what colour an opened shulker is, only its name, so
         * the block you clicked is the only reliable source. Taken from here rather than Fabric
         * API's UseBlockCallback, which would mean adding a dependency for one event.
         */
        BoxColorTracker.onBlockUsed(hitResult.getBlockPos());

        // And for Fast Rename, which needs to know which anvil you are standing at so it can spot
        // that one breaking and open whatever drops into the gap.
        FastRename.onBlockUsed(hitResult.getBlockPos());

        // And for the shulker preview: placing a box from your hand records what it carries.
        if (StorageGuiTweaks.on()) StorageMemory.onUse(hand, hitResult);

        // And for the Control Panel's stash manager, which records containers opened in a stash.
        dev.jjstools.panel.StashStore.onUse(hitResult);

        // A sign showing an item is meant to read as an item frame, so the click belongs to
        // whatever it is labelling rather than to the sign.
        if (SignToItemFrame.clickThrough(hitResult, hand)) {
            cir.setReturnValue(ActionResult.SUCCESS);
            return;
        }

        if (AntiRocketPlace.shouldIgnoreBlock()) cir.setReturnValue(ActionResult.PASS);
    }
}
