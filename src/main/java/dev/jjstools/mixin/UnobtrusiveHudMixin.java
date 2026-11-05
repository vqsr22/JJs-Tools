package dev.jjstools.mixin;

import dev.jjstools.modules.Unobtrusive;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The portal swirl and the pumpkin blur, for Unobtrusive.
 *
 * Both are the same two methods Meteor's own NoRender stops on this version, so the targets are
 * known to be right rather than worked out. The pumpkin is caught by its texture because the
 * game draws powdered snow and the rest through that same call.
 */
@Mixin(InGameHud.class)
public abstract class UnobtrusiveHudMixin {
    @Inject(method = "renderPortalOverlay", at = @At("HEAD"), cancellable = true)
    private void jjsTools$hidePortal(DrawContext context, float nauseaStrength, CallbackInfo ci) {
        if (Unobtrusive.hidePortal()) ci.cancel();
    }

    @Inject(method = "renderOverlay", at = @At("HEAD"), cancellable = true)
    private void jjsTools$hidePumpkin(DrawContext context, Identifier texture, float opacity, CallbackInfo ci) {
        if (Unobtrusive.hideOverlay(texture)) ci.cancel();
    }
}
