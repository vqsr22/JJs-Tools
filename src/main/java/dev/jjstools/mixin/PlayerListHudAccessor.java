package dev.jjstools.mixin;

import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The tab list's header and footer, which name the server even when you join through a proxy. */
@Mixin(PlayerListHud.class)
public interface PlayerListHudAccessor {
    @Accessor("header")
    Text jjstools$getHeader();

    @Accessor("footer")
    Text jjstools$getFooter();
}
