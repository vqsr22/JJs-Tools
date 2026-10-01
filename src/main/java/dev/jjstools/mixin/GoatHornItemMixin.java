package dev.jjstools.mixin;

import net.minecraft.item.Item;
import dev.jjstools.modules.BattleCry;
import net.minecraft.item.GoatHornItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * @author Tas [0xTas] <root@0xTas.dev>
 **/
@Mixin(GoatHornItem.class)
public class GoatHornItemMixin extends Item {
    // See BattleCry.java
    public GoatHornItemMixin(Settings settings) {
        super(settings);
    }

    @Inject(method = "playSound", at = @At("HEAD"), cancellable = true)
    private static void mixinPlaySound(CallbackInfo ci) {
        Modules modules = Modules.get();
        if (modules == null) return;
        BattleCry honker = modules.get(BattleCry.class);
        if (honker.shouldMuteHorns()) ci.cancel();
    }
}
