package dev.jjstools.mixin;

import dev.jjstools.modules.StorageGuiTweaks;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The icon and fill bar on shulker box and ender chest items, for Storage GUI Tweaks.
 *
 * Hung on the call that draws an item's count and durability bar, which every slot, the hotbar
 * included, makes straight after drawing the item. Drawn first thing in it, so the item's own
 * count still goes on top and stays readable.
 */
@Mixin(DrawContext.class)
public abstract class DrawContextOverlayMixin {
    @Inject(method = "drawStackOverlay(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/item/ItemStack;IILjava/lang/String;)V",
            at = @At("HEAD"))
    private void jjsTools$storageExtras(TextRenderer textRenderer, ItemStack stack, int x, int y, @Nullable String countOverride,
                                        CallbackInfo ci) {
        StorageGuiTweaks.slotExtras((DrawContext) (Object) this, stack, x, y);
    }
}
