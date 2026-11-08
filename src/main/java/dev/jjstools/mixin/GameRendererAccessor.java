package dev.jjstools.mixin;

import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The GUI's draw list, for ScreenOverlay to draw into after the GUI, as Meteor does for its screens. */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Accessor("guiState")
    GuiRenderState jjstools$getGuiState();

    @Accessor("guiRenderer")
    net.minecraft.client.gui.render.GuiRenderer jjstools$getGuiRenderer();

    @Accessor("fogRenderer")
    net.minecraft.client.render.fog.FogRenderer jjstools$getFogRenderer();
}
