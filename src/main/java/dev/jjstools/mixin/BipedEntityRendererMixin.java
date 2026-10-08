package dev.jjstools.mixin;

import dev.jjstools.modules.CustomTrims;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.item.ItemModelManager;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.state.BipedEntityRenderState;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cosmetic Trims: after your render state is filled in, swap your armour for trimmed copies.
 * Minecraft then draws the trim itself, exactly like a real trim.
 */
@Mixin(BipedEntityRenderer.class)
public abstract class BipedEntityRendererMixin {
    @Inject(method = "updateBipedRenderState", at = @At("TAIL"))
    private static void jjstools$cosmeticTrims(LivingEntity entity, BipedEntityRenderState state, float tickDelta, ItemModelManager itemModelResolver, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || entity != mc.player) return;

        state.equippedHeadStack = CustomTrims.trimmedForPlayer(state.equippedHeadStack, EquipmentSlot.HEAD);
        state.equippedChestStack = CustomTrims.trimmedForPlayer(state.equippedChestStack, EquipmentSlot.CHEST);
        state.equippedLegsStack = CustomTrims.trimmedForPlayer(state.equippedLegsStack, EquipmentSlot.LEGS);
        state.equippedFeetStack = CustomTrims.trimmedForPlayer(state.equippedFeetStack, EquipmentSlot.FEET);
    }
}
