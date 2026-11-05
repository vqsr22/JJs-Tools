package dev.jjstools.mixin;

import dev.jjstools.modules.SignToItemFrame;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.BlockModelRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Keeps a sign's board out of the chunk, for Sign to Item Frame.
 *
 * The board is not drawn with the writing. The writing comes from the block entity renderer and
 * the board is baked into the chunk mesh like any ordinary block, which is why cancelling the
 * one took the text and left the board standing there.
 *
 * Finding the right place to stop it took reading how Meteor's own Xray hides a block on this
 * version, which turned out not to go near the block render manager: the chunk builder reaches
 * straight past it into these two, one for smooth lighting and one for flat. Both are covered
 * here because which one runs depends on a graphics setting.
 *
 * These run on the chunk building threads, not the render thread, so everything they touch on
 * the way through has to be safe to touch from several at once. A mesh is also built once and
 * kept, so the answer given here is frozen into it until that chunk is built again.
 */
@Mixin(BlockModelRenderer.class)
public abstract class BlockModelRendererMixin {
    @Inject(method = "renderSmooth", at = @At("HEAD"), cancellable = true, require = 0)
    private void jjsTools$hideSignBoardSmooth(BlockRenderView world, List<?> parts, BlockState state,
                                              BlockPos pos, MatrixStack matrices, VertexConsumer vertexConsumer,
                                              boolean cull, int overlay, CallbackInfo ci) {
        if (SignToItemFrame.hideBoard(state, pos, world)) ci.cancel();
    }

    @Inject(method = "renderFlat", at = @At("HEAD"), cancellable = true, require = 0)
    private void jjsTools$hideSignBoardFlat(BlockRenderView world, List<?> parts, BlockState state,
                                            BlockPos pos, MatrixStack matrices, VertexConsumer vertexConsumer,
                                            boolean cull, int overlay, CallbackInfo ci) {
        if (SignToItemFrame.hideBoard(state, pos, world)) ci.cancel();
    }

    /**
     * The last resort, and the one Xray itself leans on.
     *
     * If the two above ever stop being the way in, every face still goes through this, so a
     * sign whose board slipped past them loses its faces one at a time instead.
     */
    @Inject(method = "shouldDrawFace", at = @At("HEAD"), cancellable = true, require = 0)
    private static void jjsTools$hideSignFace(BlockRenderView world, BlockState state, boolean cull,
                                              net.minecraft.util.math.Direction direction, BlockPos pos,
                                              org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        /*
         * The position here is the neighbour the face looks at, not the block being drawn, so
         * the sign is the block on the other side of that face.
         */
        BlockPos self = pos.offset(direction.getOpposite());
        if (SignToItemFrame.hideBoard(state, self, world)) cir.setReturnValue(false);
    }
}
