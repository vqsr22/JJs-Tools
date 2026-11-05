package dev.jjstools.mixin;

import dev.jjstools.modules.AutoDoor;
import net.minecraft.block.DoorBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mutes door sounds for Auto Door's mute-doors setting. Same hook Stardust uses. */
@Mixin(DoorBlock.class)
public abstract class DoorBlockMixin {
    @Inject(method = "playOpenCloseSound", at = @At("HEAD"), cancellable = true)
    private void jjstools$mute(CallbackInfo ci) {
        AutoDoor autoDoor = AutoDoor.get();
        if (autoDoor != null && autoDoor.shouldMute()) ci.cancel();
    }
}
