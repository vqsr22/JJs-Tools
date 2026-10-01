package dev.jjstools.mixin;

import dev.jjstools.modules.EnchantFix;
import dev.jjstools.util.VanillaEnchants;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.component.ComponentsAccess;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.Item;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Writes enchantment lines in vanilla order by ID instead of the (scrambled) #minecraft:tooltip_order tag.
 * Covers minecraft:enchantments (all enchantable items) and minecraft:stored_enchantments (books).
 * Modded enchantments go last, in their original order.
 */
@Mixin(ItemEnchantmentsComponent.class)
public abstract class ItemEnchantmentsComponentMixin {
    @Inject(method = "appendTooltip", at = @At("HEAD"), cancellable = true)
    private void jjstools$vanillaOrder(Item.TooltipContext context, Consumer<Text> textConsumer,
                                       TooltipType type, ComponentsAccess components, CallbackInfo ci) {
        if (!EnchantFix.shouldFixOrder()) return;

        ItemEnchantmentsComponent self = (ItemEnchantmentsComponent) (Object) this;
        List<Object2IntMap.Entry<RegistryEntry<Enchantment>>> entries = new ArrayList<>(self.getEnchantmentEntries());
        entries.sort(Comparator.comparingInt(e -> VanillaEnchants.orderIndex(e.getKey())));

        for (Object2IntMap.Entry<RegistryEntry<Enchantment>> e : entries) {
            int level = e.getIntValue();
            if (level > 0) textConsumer.accept(Enchantment.getName(e.getKey(), level));
        }
        ci.cancel();
    }
}
