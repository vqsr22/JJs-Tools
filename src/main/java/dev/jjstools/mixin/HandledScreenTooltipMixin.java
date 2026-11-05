package dev.jjstools.mixin;

import dev.jjstools.modules.StorageGuiTweaks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shows a hovered shulker box or ender chest as its own GUI instead of a tooltip, for Storage GUI
 * Tweaks.
 *
 * Taken over where the screen draws the tooltip for the slot under the mouse, so when the
 * preview is drawn nothing else is: no item name, no lore, and nothing other mods add to the
 * tooltip either. Not while an item is held on the cursor, when vanilla's own tooltip is about
 * where that item would go rather than about the slot.
 */
@Mixin(HandledScreen.class)
public abstract class HandledScreenTooltipMixin {
    @Shadow protected Slot focusedSlot;
    @Shadow @Final protected ScreenHandler handler;

    @Inject(method = "drawMouseoverTooltip", at = @At("HEAD"), cancellable = true)
    private void jjsTools$storagePreview(DrawContext context, int x, int y, CallbackInfo ci) {
        if (focusedSlot == null || !focusedSlot.hasStack()) return;
        if (!handler.getCursorStack().isEmpty()) return;

        if (StorageGuiTweaks.inventoryPreview(context, focusedSlot.getStack(), x, y)) ci.cancel();
    }
}
