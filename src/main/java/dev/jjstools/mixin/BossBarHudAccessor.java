package dev.jjstools.mixin;

import net.minecraft.client.gui.hud.BossBarHud;
import net.minecraft.client.gui.hud.ClientBossBar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;

/**
 * The boss bar map is filled from server packets, separately from drawing.
 * Hiding boss bars (Meteor NoRender, F1) only skips the draw, so this map still
 * holds the raid bar.
 */
@Mixin(BossBarHud.class)
public interface BossBarHudAccessor {
    @Accessor("bossBars")
    Map<UUID, ClientBossBar> jjstools$getBossBars();
}
