package dev.jjstools.mixin;

import dev.jjstools.modules.EnchantFix;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Enchant Fix option: vanilla puts a renamed item's name in italics. This turns that off on
 * the first tooltip line (the name). Colours and any formatting inside the name are kept.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackTooltipMixin {
    @Inject(method = "getTooltip", at = @At("RETURN"))
    private void jjstools$noItalicName(Item.TooltipContext context, PlayerEntity player, TooltipType type, CallbackInfoReturnable<List<Text>> cir) {
        if (!EnchantFix.shouldUnitaliciseNames()) return;

        ItemStack self = (ItemStack) (Object) this;
        if (!self.contains(DataComponentTypes.CUSTOM_NAME)) return;

        List<Text> lines = cir.getReturnValue();
        if (lines == null || lines.isEmpty()) return;

        try {
            lines.set(0, lines.get(0).copy().styled(style -> style.withItalic(false)));
        } catch (UnsupportedOperationException ignored) {
            // Another mod returned a read-only list; leave it as is.
        }
    }
}
