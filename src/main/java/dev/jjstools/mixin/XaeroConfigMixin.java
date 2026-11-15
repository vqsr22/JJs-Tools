package dev.jjstools.mixin;

import dev.jjstools.util.XaeroIncognito;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.lib.common.config.option.ConfigOption;

/**
 * Where Xaero's World Map and Minimap read their settings while you play. While Incognito hides
 * them, the cursor coordinates and the waypoints on both maps read as off. Nothing is written to
 * Xaero's own config, so turning Incognito off puts everything back as it was. Skipped entirely
 * when Xaero's mods are not installed.
 */
@Pseudo
@Mixin(targets = "xaero.lib.client.config.ClientConfigManager", remap = false)
public abstract class XaeroConfigMixin {
    @Inject(method = "getEffective(Lxaero/lib/common/config/option/ConfigOption;)Ljava/lang/Object;", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void jjsTools$incognito(ConfigOption<?> option, CallbackInfoReturnable<Object> cir) {
        if (XaeroIncognito.hides(option)) cir.setReturnValue(Boolean.FALSE);
    }
}
