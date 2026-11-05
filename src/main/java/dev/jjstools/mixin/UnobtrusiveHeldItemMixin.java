package dev.jjstools.mixin;

import dev.jjstools.modules.Unobtrusive;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The shield and the food in your hands, for Unobtrusive.
 *
 * Both hooks sit exactly where Meteor's own Hand View puts its equivalents on this version, read
 * out of its jar: one just before the held item is drawn, inside vanilla's own push and pop so a
 * shift there cannot leak into anything else, and one in the eating animation at the point that
 * separates the bob from the turn.
 */
@Mixin(HeldItemRenderer.class)
public abstract class UnobtrusiveHeldItemMixin {
    @Inject(
        method = "renderFirstPersonItem",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/item/HeldItemRenderer;renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemDisplayContext;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V",
            shift = At.Shift.BEFORE
        )
    )
    private void jjsTools$moveShield(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand,
                                     float swingProgress, ItemStack item, float equipProgress, MatrixStack matrices,
                                     OrderedRenderCommandQueue queue, int light, CallbackInfo ci) {
        float shift = Unobtrusive.shieldOffset(item);
        if (shift != 0.0F) matrices.translate(0.0F, shift, 0.0F);
    }

    /**
     * Limits how far a held item dips, on the way down and back up.
     *
     * Changed where vanilla hands each hand its dip, rather than inside the method that uses it,
     * because that call also says which hand it is for: the offhand's dip can only be a switch,
     * the main hand's can be either. Capping it there caps it for everything drawn for that
     * hand, so the item, the shield and the food all dip by the same reduced amount.
     */
    @ModifyArg(
        method = "renderItem(FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/network/ClientPlayerEntity;I)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/item/HeldItemRenderer;renderFirstPersonItem(Lnet/minecraft/client/network/AbstractClientPlayerEntity;FFLnet/minecraft/util/Hand;FLnet/minecraft/item/ItemStack;FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V"
        ),
        index = 6
    )
    private float jjsTools$limitDip(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand,
                                    float swingProgress, ItemStack item, float equipProgress, MatrixStack matrices,
                                    OrderedRenderCommandQueue queue, int light) {
        return Unobtrusive.limitDip(equipProgress, hand);
    }

    /**
     * Takes over the eating animation after its bob.
     *
     * Vanilla works out the bob, then calls Math.pow to ease the lift toward your mouth, then
     * turns the item. Stopping just before that call keeps the bob and hands everything after it
     * to Unobtrusive, which does the lift itself and turns the item level instead.
     */
    @Inject(
        method = "applyEatOrDrinkTransformation",
        at = @At(value = "INVOKE", target = "Ljava/lang/Math;pow(DD)D", shift = At.Shift.BEFORE),
        cancellable = true
    )
    private void jjsTools$centreFood(MatrixStack matrices, float tickDelta, Arm arm, ItemStack stack,
                                     PlayerEntity player, CallbackInfo ci) {
        if (Unobtrusive.centreEating(matrices, tickDelta, arm, stack, player)) ci.cancel();
    }
}
