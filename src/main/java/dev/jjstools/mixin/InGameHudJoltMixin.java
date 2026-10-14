package dev.jjstools.mixin;

import dev.jjstools.modules.NoJolt;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Takes the shake out of the health and hunger icons.
 *
 * Both jolts are a random offset added to an icon's Y as it is drawn, and both come from the HUD's
 * own random. The bound says which is which, so one redirect covers both: the hearts ask for a
 * number under two, the food icons for one under three. Returning the value that works out to no
 * offset leaves the icon where it would have sat anyway, so nothing else about the bar changes.
 *
 * Anything else drawing from that random keeps its real numbers.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudJoltMixin {
    @Redirect(
        method = {"renderStatusBars", "renderHealthBar"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/random/Random;nextInt(I)I")
    )
    private int jjsTools$steadyIcons(Random random, int bound) {
        // Hearts: the offset is the value itself, so zero holds them still.
        if (bound == 2 && NoJolt.steadyHearts()) return 0;

        // Food: the offset is the value minus one, so one holds them still.
        if (bound == 3 && NoJolt.steadyHunger()) return 1;

        return random.nextInt(bound);
    }
}
