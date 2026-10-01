package dev.jjstools.mixin;

import dev.jjstools.modules.TabOptimizer;
import dev.jjstools.tab.LiteTab;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tablist Optimisations fade: tracks the fade every frame, and keeps drawing the list for a moment
 * after tab is let go (vanilla stops drawing it straight away) so it can fade out.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudTabMixin {
    @Shadow @Final private PlayerListHud playerListHud;

    @Inject(method = "renderPlayerList", at = @At("HEAD"))
    private void jjstools$tickFade(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean lite = TabOptimizer.on() && TabOptimizer.get().liteRenderer.get();
        LiteTab.tickFade(lite && mc.options.playerListKey.isPressed(),
            lite ? TabOptimizer.get().fadeIn.get() : 0,
            lite ? TabOptimizer.get().fadeOut.get() : 0);
    }

    @Inject(method = "renderPlayerList", at = @At("RETURN"))
    private void jjstools$fadeOut(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.options.playerListKey.isPressed() || !LiteTab.fadingOut()) return;
        if (!TabOptimizer.on() || !TabOptimizer.get().liteRenderer.get()) return;

        Scoreboard scoreboard = mc.world.getScoreboard();
        playerListHud.render(context, context.getScaledWindowWidth(), scoreboard, scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.LIST));
    }
}
