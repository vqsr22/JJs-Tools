package dev.jjstools.mixin.accessor;

import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The block an item would place for a click, worked out by the item itself.
 *
 * Protected on BlockItem. Asking the item rather than the block matters for torches, signs,
 * banners and the like, whose item picks between a standing block and a wall one, and it also
 * includes the item's own check that the block could stand there. Printer Go Brrr uses it to
 * know exactly what a click will place before making it.
 */
@Mixin(BlockItem.class)
public interface BlockItemInvoker {
    @Invoker("getPlacementState")
    BlockState jjsTools$getPlacementState(ItemPlacementContext context);
}
