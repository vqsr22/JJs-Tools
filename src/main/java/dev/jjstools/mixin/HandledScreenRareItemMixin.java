package dev.jjstools.mixin;

import dev.jjstools.modules.RareItemHighlighter;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rare Item Finder highlight, drawn as the slot background before anything in the slot.
 *
 * It used to hook every item draw, so when another mod drew an extra item on top of a slot
 * (like a shulker's content preview) the highlight was drawn again over the shulker. Hooking
 * the slot instead means it is drawn once per slot, behind the item and anything drawn on it.
 */
@Mixin(HandledScreen.class)
public abstract class HandledScreenRareItemMixin {
    @Inject(method = "drawSlot", at = @At("HEAD"))
    private void jjstools$rareItemBackground(DrawContext context, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        Modules modules = Modules.get();
        if (modules == null) return;

        RareItemHighlighter finder = modules.get(RareItemHighlighter.class);
        if (finder == null || !finder.isActive() || !finder.shouldHighlightSlot(slot.getStack())) return;

        context.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, finder.color.get().getPacked());
    }
}
