package dev.jjstools.mixin;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import net.minecraft.client.gui.DrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Meteor's GUI renderer's drawing context, for the fonts tab to draw each font's name in that
 * font with the Control Panel's own font renderer, after Meteor has drawn its own text.
 */
@Mixin(value = GuiRenderer.class, remap = false)
public interface GuiRendererAccessor {
    @Accessor(value = "drawContext", remap = false)
    DrawContext jjstools$getDrawContext();
}
