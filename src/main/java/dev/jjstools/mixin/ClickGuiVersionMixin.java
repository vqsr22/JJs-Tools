package dev.jjstools.mixin;

import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shows which version of JJ's Tools is running in the bottom left of Meteor's click GUI.
 *
 * Meteor draws its screens in renderCustom, a method of its own rather than the game's, so the
 * name is not remapped. The text goes on after everything else, in the same draw, on every tab
 * of the click GUI.
 */
@Mixin(value = WidgetScreen.class, remap = false)
public abstract class ClickGuiVersionMixin {
    @Unique
    private static String jjsTools$label;

    @Inject(method = "renderCustom", at = @At("TAIL"))
    private void jjsTools$drawVersion(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!((Object) this instanceof TabScreen)) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        int y = context.getScaledWindowHeight() - mc.textRenderer.fontHeight - 3;
        context.drawTextWithShadow(mc.textRenderer, jjsTools$label(), 4, y, 0xFFB4B4B4);
    }

    /** "JJ's Tools 0.5-beta", from the mod's own version with the Minecraft version left off. */
    @Unique
    private static String jjsTools$label() {
        if (jjsTools$label == null) {
            String version = FabricLoader.getInstance().getModContainer("jjs-tools")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
            int plus = version.indexOf('+');
            if (plus > 0) version = version.substring(0, plus);
            jjsTools$label = "JJ's Tools " + version;
        }
        return jjsTools$label;
    }
}
