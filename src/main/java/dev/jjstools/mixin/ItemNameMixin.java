package dev.jjstools.mixin;

import dev.jjstools.modules.ViaTextureFix;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.regex.Matcher;

/**
 * Strips the "1.21.2 " that ViaBackwards prepends to items it had to translate down.
 *
 * Done at display time rather than by editing the stack, because changing the actual component
 * would leave the client holding a different item to the server.
 */
@Mixin(ItemStack.class)
public class ItemNameMixin {
    @Inject(method = "getName", at = @At("RETURN"), cancellable = true)
    private void jjsTools$stripVersionPrefix(CallbackInfoReturnable<Text> cir) {
        if (!ViaTextureFix.shouldStrip()) return;

        Text name = cir.getReturnValue();
        if (name == null) return;

        String plain = name.getString();
        Matcher matcher = ViaTextureFix.VERSION_PREFIX.matcher(plain);
        if (!matcher.find()) return;

        cir.setReturnValue(Text.literal(plain.substring(matcher.end())).setStyle(name.getStyle()));
    }
}
