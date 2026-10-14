package dev.jjstools.mixin;

import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets Meteor's GUI scale go down to 0.5 instead of stopping at 0.75.
 *
 * The floor lives on the setting itself rather than anywhere it is read, so it is lowered as the
 * setting is built. Matching on the name and on the existing floor keeps this to the one setting
 * it is meant for: another double that happens to start at 0.75 is left where it is, and if Meteor
 * ever lowers the floor itself this stops doing anything rather than fighting it.
 *
 * Nothing about how the scale is applied changes, only how far the slider goes.
 */
@Mixin(value = DoubleSetting.class, remap = false)
public abstract class DoubleSettingScaleMixin {
    @Shadow @Final @Mutable public double min;
    @Shadow @Final @Mutable public double sliderMin;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void jjsTools$lowerGuiScaleFloor(CallbackInfo ci) {
        Setting<?> self = (Setting<?>) (Object) this;
        if (!"scale".equals(self.name)) return;

        if (min == 0.75) min = 0.5;
        if (sliderMin == 0.75) sliderMin = 0.5;
    }
}
