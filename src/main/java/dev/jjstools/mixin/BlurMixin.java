package dev.jjstools.mixin;

import dev.jjstools.util.BlurExtras;
import meteordevelopment.meteorclient.systems.modules.render.Blur;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Meteor's Blur only blurs behind screens. This also turns it on while the tab list is held open
 * (no screen open, HUD visible), when Blur's Extras > tab-list is on. Uses Blur's own fade.
 */
@Mixin(value = Blur.class, remap = false)
public abstract class BlurMixin {
    @Inject(method = "shouldRender", at = @At("RETURN"), cancellable = true)
    private void jjstools$blurTabList(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) return;
        if (!((Blur) (Object) this).isActive() || !BlurExtras.tabList()) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.currentScreen != null || mc.options.hudHidden) return;
        if (mc.options.playerListKey.isPressed()) cir.setReturnValue(true);
    }
}
