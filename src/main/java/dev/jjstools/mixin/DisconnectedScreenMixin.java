package dev.jjstools.mixin;

import dev.jjstools.util.DisconnectReason;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the cause DisconnectReason noted under the game's disconnect message. */
@Mixin(DisconnectedScreen.class)
public abstract class DisconnectedScreenMixin {
    @Shadow @Final @Mutable private DisconnectionInfo info;

    @Inject(method = "<init>(Lnet/minecraft/client/gui/screen/Screen;Lnet/minecraft/text/Text;Lnet/minecraft/network/DisconnectionInfo;Lnet/minecraft/text/Text;)V", at = @At("RETURN"), require = 0)
    private void jjsTools$reason(Screen parent, Text title, DisconnectionInfo given, Text button, CallbackInfo ci) {
        try {
            Text extended = DisconnectReason.extend(info.reason());
            if (extended != info.reason()) info = new DisconnectionInfo(extended, info.report(), info.bugReportLink());
        } catch (Throwable ignored) {
        }
    }
}
