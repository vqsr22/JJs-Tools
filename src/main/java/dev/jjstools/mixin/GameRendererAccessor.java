package dev.jjstools.mixin;

//#if MC >= 1.21.6
import net.minecraft.client.gui.render.state.GuiRenderState;
//#endif
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The GUI's draw list, for ScreenOverlay to draw into after the GUI, as Meteor does for its screens. */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    // Before 1.21.6 the GUI is drawn straight away rather than through a draw list, so there is
    // nothing to reach here.
    //#if MC >= 1.21.6
    @Accessor("guiState")
    GuiRenderState jjstools$getGuiState();

    @Accessor("guiRenderer")
    net.minecraft.client.gui.render.GuiRenderer jjstools$getGuiRenderer();

    @Accessor("fogRenderer")
    net.minecraft.client.render.fog.FogRenderer jjstools$getFogRenderer();
    //#endif
}
