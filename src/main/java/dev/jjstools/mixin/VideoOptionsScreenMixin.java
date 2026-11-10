package dev.jjstools.mixin;

import dev.jjstools.util.Borderless;
import net.minecraft.client.gui.screen.option.VideoOptionsScreen;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.SimpleOption;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;

/** Adds Borderless Windowed to the Display section of Video Settings, after the game's own options. */
@Mixin(VideoOptionsScreen.class)
public abstract class VideoOptionsScreenMixin {
    //#if MC >= 1.21.9
    @Inject(method = "getDisplayOptions", at = @At("RETURN"), cancellable = true)
    //#else
    //$$ // One list of options before the video settings were split into sections.
    //$$ @Inject(method = "getOptions", at = @At("RETURN"), cancellable = true)
    //#endif
    private static void jjsTools$addBorderless(GameOptions options, CallbackInfoReturnable<SimpleOption<?>[]> cir) {
        SimpleOption<?>[] list = cir.getReturnValue();
        SimpleOption<?>[] more = Arrays.copyOf(list, list.length + 1);
        more[list.length] = Borderless.OPTION;
        cir.setReturnValue(more);
    }
}
