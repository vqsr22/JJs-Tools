package dev.jjstools.mixin;

import dev.jjstools.modules.SignToItemFrame;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Keeps the sign board itself out of the chunk, for Sign to Item Frame.
 *
 * Cancelling the block entity renderer takes the writing away and leaves the board standing,
 * which was the puzzle: the board is not drawn with the writing at all. It is baked into the
 * chunk mesh like any ordinary block, and this is the one call that puts it there.
 *
 * That has a consequence worth knowing. A chunk mesh is built once and kept, so the decision
 * made here is frozen into it until the chunk is built again, which is why toggling the module
 * asks the renderer to start over.
 *
 * This runs on the chunk building threads rather than on the render thread, so everything it
 * touches on the way through has to be safe to touch from several at once.
 */
@Mixin(BlockRenderManager.class)
public abstract class BlockRenderManagerMixin {
    @Inject(method = "renderBlock", at = @At("HEAD"), cancellable = true)
    private void jjsTools$hideSignBoard(BlockState state, BlockPos pos, BlockRenderView world,
                                        MatrixStack matrices, VertexConsumer vertexConsumer,
                                        boolean cull, List<?> parts, CallbackInfo ci) {
        if (SignToItemFrame.hideBoard(state, pos, world)) ci.cancel();
    }
}
