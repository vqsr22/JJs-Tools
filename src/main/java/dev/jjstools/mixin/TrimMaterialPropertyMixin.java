package dev.jjstools.mixin;

import dev.jjstools.modules.CustomTrims;
import net.minecraft.client.render.item.property.select.TrimMaterialProperty;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
//#if MC >= 1.21.5
import net.minecraft.item.ItemDisplayContext;
//#else
//$$ import net.minecraft.item.ModelTransformationMode;
//#endif
import net.minecraft.item.ItemStack;
import net.minecraft.item.equipment.trim.ArmorTrimMaterial;
import net.minecraft.registry.RegistryKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cosmetic Trims: the armour you wear shows the chosen trim colour on its item icon. */
@Mixin(TrimMaterialProperty.class)
public abstract class TrimMaterialPropertyMixin {
    //#if MC >= 1.21.5
    @Inject(method = "getValue(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/world/ClientWorld;Lnet/minecraft/entity/LivingEntity;ILnet/minecraft/item/ItemDisplayContext;)Lnet/minecraft/registry/RegistryKey;", at = @At("RETURN"), cancellable = true, require = 0)
    private void jjstools$iconTrim(ItemStack stack, ClientWorld world, LivingEntity entity, int seed, ItemDisplayContext context, CallbackInfoReturnable<RegistryKey<ArmorTrimMaterial>> cir) {
    //#else
    //$$ @Inject(method = "getValue(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/world/ClientWorld;Lnet/minecraft/entity/LivingEntity;ILnet/minecraft/item/ModelTransformationMode;)Lnet/minecraft/registry/RegistryKey;", at = @At("RETURN"), cancellable = true, require = 0)
    //$$ private void jjstools$iconTrim(ItemStack stack, ClientWorld world, LivingEntity entity, int seed, ModelTransformationMode context, CallbackInfoReturnable<RegistryKey<ArmorTrimMaterial>> cir) {
    //#endif
        RegistryKey<ArmorTrimMaterial> key = CustomTrims.iconMaterial(stack);
        if (key != null) cir.setReturnValue(key);
    }
}
