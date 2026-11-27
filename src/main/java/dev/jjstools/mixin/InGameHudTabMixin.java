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
 * The tab list on top of everything, and Optimise Tablist's fade.
 *
 * The game draws the tab list partway through its HUD, and Meteor's HUD (HUD elements, the
 * crosshair preview) comes after, at the end, so it covered the list. The list is skipped where
 * the game draws it and drawn at the very end instead, on a layer of its own above the rest.
 * Priority 2000 so that comes after Meteor's own end-of-HUD drawing.
 *
 * The fade: tracked every frame, and the list kept on screen for a moment after tab is let go
 * (vanilla stops drawing it straight away) so it can fade out.
 */
@Mixin(value = InGameHud.class, priority = 2000)
public abstract class InGameHudTabMixin {
    @Shadow @Final private PlayerListHud playerListHud;

    @Shadow
    protected abstract void renderPlayerList(DrawContext context, RenderTickCounter tickCounter);

    @org.spongepowered.asm.mixin.Unique
    private boolean jjstools$drawingLast;
    @org.spongepowered.asm.mixin.Unique
    private RenderTickCounter jjstools$skipped;

    @Inject(method = "renderPlayerList", at = @At("HEAD"), cancellable = true)
    private void jjstools$tickFade(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (!jjstools$drawingLast) {
            // Drawn at the end instead, above everything else.
            jjstools$skipped = tickCounter;
            ci.cancel();
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean lite = TabOptimizer.on() && TabOptimizer.get().liteRenderer.get();
        LiteTab.tickFade(lite && mc.options.playerListKey.isPressed(),
            lite ? TabOptimizer.get().fadeIn.get() : 0,
            lite ? TabOptimizer.get().fadeOut.get() : 0);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void jjstools$tabLast(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        RenderTickCounter counter = jjstools$skipped;
        if (counter == null) return;
        jjstools$skipped = null;
        dev.jjstools.compat.Gui.newLayer(context);
        jjstools$drawingLast = true;
        try {
            renderPlayerList(context, counter);
        } finally {
            jjstools$drawingLast = false;
        }
        // Lite tab's names in Meteor's font are queued as it draws and drawn by Meteor's HUD
        // pass, which has been and gone by now: drawn here, after the list, instead. Meteor's
        // font draws at once, so the list's panel, still waiting to be drawn, is drawn first.
        //#if MC >= 1.21.11
        ((meteordevelopment.meteorclient.mixininterface.IGameRenderer) MinecraftClient.getInstance().gameRenderer).meteor$flushGuiState();
        context.createNewRootLayer();
        //#elif MC < 1.21.6
        //$$ context.draw();
        //#endif
        meteordevelopment.meteorclient.utils.Utils.unscaledProjection();
        try {
            dev.jjstools.tab.LiteTab.drawQueuedText();
        } finally {
            meteordevelopment.meteorclient.utils.Utils.scaledProjection();
        }
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
