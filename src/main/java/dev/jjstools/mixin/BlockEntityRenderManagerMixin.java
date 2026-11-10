package dev.jjstools.mixin;

import dev.jjstools.modules.SignToItemFrame;
//#if MC >= 1.21.9
import net.minecraft.client.render.block.entity.BlockEntityRenderManager;
import net.minecraft.client.render.block.entity.state.BlockEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
//#else
//$$ import net.minecraft.block.entity.BlockEntity;
//$$ import net.minecraft.client.render.VertexConsumerProvider;
//$$ import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
//#endif
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Where Sign to Item Frame gets its chance to draw.
 *
 * Earlier versions hooked the sign renderer itself, which is the obvious place and was never
 * once called: something between the shared base class and the concrete one was not what it
 * appeared to be, and a hook that never fires gives you nothing to go on. This is the single
 * method every block entity in the world goes through, so it cannot quietly miss, and anything
 * wrong from here shows up as an item in the wrong place rather than as nothing at all.
 *
 * The parameters have to be the exact types the method declares, Object will not stand in for
 * one: Mixin checks the handler's descriptor against the target's and refuses the whole mixin
 * if they differ.
 *
 * Three things happen around the one call. The item is drawn before the sign, so the sign can be
 * made to go away afterwards; a sign being hidden outright is cancelled here rather than drawn
 * and covered up; and a sign keeping its board but losing its writing has that writing put back
 * on the way out, so nothing is left altered between frames.
 */
//#if MC >= 1.21.9
@Mixin(BlockEntityRenderManager.class)
//#else
//$$ @Mixin(BlockEntityRenderDispatcher.class)
//#endif
public abstract class BlockEntityRenderManagerMixin {
    //#if MC >= 1.21.9
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void jjsTools$drawSignItem(BlockEntityRenderState state, MatrixStack matrices,
                                       OrderedRenderCommandQueue queue, CameraRenderState cameraRenderState,
                                       CallbackInfo ci) {
        if (SignToItemFrame.onBlockEntity(state, matrices, queue)) ci.cancel();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void jjsTools$restoreSignText(BlockEntityRenderState state, MatrixStack matrices,
                                          OrderedRenderCommandQueue queue, CameraRenderState cameraRenderState,
                                          CallbackInfo ci) {
        SignToItemFrame.afterBlockEntity(state);
    }
    //#else
    //$$ // Before 1.21.9 the dispatcher draws the block entity itself, with the matrices already
    //$$ // standing at the block, as the render state version is handed them.
    //$$ @Inject(method = "render(Lnet/minecraft/block/entity/BlockEntity;FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;)V", at = @At("HEAD"), cancellable = true)
    //$$ private void jjsTools$drawSignItem(BlockEntity blockEntity, float tickDelta, MatrixStack matrices,
    //$$                                    VertexConsumerProvider vertices, CallbackInfo ci) {
    //$$     if (SignToItemFrame.onBlockEntity(blockEntity, matrices, vertices)) ci.cancel();
    //$$ }
    //$$
    //$$ @Inject(method = "render(Lnet/minecraft/block/entity/BlockEntity;FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;)V", at = @At("RETURN"))
    //$$ private void jjsTools$restoreSignText(BlockEntity blockEntity, float tickDelta, MatrixStack matrices,
    //$$                                       VertexConsumerProvider vertices, CallbackInfo ci) {
    //$$     SignToItemFrame.afterBlockEntity(blockEntity);
    //$$ }
    //#endif
}
