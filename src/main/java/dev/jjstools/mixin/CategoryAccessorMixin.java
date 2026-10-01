package dev.jjstools.mixin;

import dev.jjstools.util.ICategoryIcon;
import meteordevelopment.meteorclient.systems.modules.Category;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Category.icon is final, so replacing a category's icon needs the field opened up.
 * Reflection cannot set a final instance field on modern Java, which is why this is a mixin.
 */
@Mixin(value = Category.class, remap = false)
public class CategoryAccessorMixin implements ICategoryIcon {
    @Shadow @Final @Mutable
    public ItemStack icon;

    @Override
    public void jjsTools$setIcon(ItemStack stack) {
        this.icon = stack;
    }
}
