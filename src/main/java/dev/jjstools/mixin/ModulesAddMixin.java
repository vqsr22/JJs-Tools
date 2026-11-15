package dev.jjstools.mixin;

import dev.jjstools.util.ModuleRenames;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Catches a module as it is registered, which is the only moment a name clash can still be fixed.
 *
 * Meteor files modules by name. Two add-ons registering the same name does not give you two
 * entries to pick between: the second one takes the first one's place and the first is simply
 * gone, which is why a clash looks like one add-on's module having vanished rather than like a
 * conflict. Nothing later can recover it, because by then there is nothing left to find.
 *
 * Renaming here, before Meteor files it, means both survive, and both are filed and saved under
 * their own name from the first launch rather than being patched up afterwards.
 */
@Mixin(value = Modules.class, remap = false)
public class ModulesAddMixin {
    @Inject(method = "add(Lmeteordevelopment/meteorclient/systems/modules/Module;)V", at = @At("HEAD"))
    private void jjsTools$beforeAdd(Module module, CallbackInfo ci) {
        ModuleRenames.onRegister(module);
    }

    /** See ModuleConfigBackup: what Meteor's saved file has, before it loads it. */
    @Inject(method = "fromTag", at = @At("HEAD"))
    private void jjsTools$beforeLoad(net.minecraft.nbt.NbtCompound tag, CallbackInfoReturnable<Modules> cir) {
        dev.jjstools.util.ModuleConfigBackup.beforeLoad(tag);
    }

    @Inject(method = "fromTag", at = @At("RETURN"))
    private void jjsTools$afterLoad(net.minecraft.nbt.NbtCompound tag, CallbackInfoReturnable<Modules> cir) {
        dev.jjstools.util.ModuleConfigBackup.afterLoad();
    }

    @Inject(method = "toTag", at = @At("RETURN"))
    private void jjsTools$afterSave(CallbackInfoReturnable<net.minecraft.nbt.NbtCompound> cir) {
        dev.jjstools.util.ModuleConfigBackup.afterSave(cir.getReturnValue());
    }
}
