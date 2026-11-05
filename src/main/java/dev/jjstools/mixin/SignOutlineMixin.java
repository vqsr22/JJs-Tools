package dev.jjstools.mixin;

import dev.jjstools.modules.SignToItemFrame;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets your crosshair pass through a sign that Sign to Item Frame shows as an item.
 *
 * The crosshair finds blocks by their outline shape, and so does the box drawn around what you
 * are looking at. An empty outline for these signs means both skip them: what you aim at, open
 * and preview is the chest behind. Signs have no collision to begin with, so nothing about
 * walking into them changes.
 */
@Mixin(AbstractBlock.AbstractBlockState.class)
public abstract class SignOutlineMixin {
    @Inject(method = "getOutlineShape(Lnet/minecraft/world/BlockView;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/ShapeContext;)Lnet/minecraft/util/shape/VoxelShape;",
            at = @At("HEAD"), cancellable = true)
    private void jjsTools$passThroughSign(BlockView world, BlockPos pos, ShapeContext context, CallbackInfoReturnable<VoxelShape> cir) {
        if (SignToItemFrame.passThrough((BlockState) (Object) this, pos, world)) cir.setReturnValue(VoxelShapes.empty());
    }
}
