package dev.jjstools.mixin;

import dev.jjstools.modules.TabOptimizer;
import dev.jjstools.tab.HeadAtlas;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Head savings that keep heads visible: drop the hat overlay (a second quad per
 * head) and stop after head-limit heads. Both SkinTextures overloads are covered
 * with require = 0 since one may delegate to the other; the inHead flag stops a
 * delegating call from counting twice.
 */
@Mixin(PlayerSkinDrawer.class)
public abstract class PlayerSkinDrawerMixin {

    @Inject(method = "draw(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/entity/player/SkinTextures;III)V", at = @At("HEAD"), cancellable = true, require = 0)
    private static void jjstools$head(DrawContext context, SkinTextures textures, int x, int y, int size, CallbackInfo ci) {
        if (jjstools$blocked()) ci.cancel();
    }

    @Inject(method = "draw(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/entity/player/SkinTextures;IIII)V", at = @At("HEAD"), cancellable = true, require = 0)
    private static void jjstools$headColored(DrawContext context, SkinTextures textures, int x, int y, int size, int color, CallbackInfo ci) {
        if (jjstools$blocked()) ci.cancel();
    }

    @Inject(method = "draw(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/entity/player/SkinTextures;III)V", at = @At("RETURN"), require = 0)
    private static void jjstools$headDone(DrawContext context, SkinTextures textures, int x, int y, int size, CallbackInfo ci) {
        TabOptimizer.inHead = false;
    }

    @Inject(method = "draw(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/entity/player/SkinTextures;IIII)V", at = @At("RETURN"), require = 0)
    private static void jjstools$headColoredDone(DrawContext context, SkinTextures textures, int x, int y, int size, int color, CallbackInfo ci) {
        TabOptimizer.inHead = false;
    }

    @Inject(method = "drawHat(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/util/Identifier;IIIZI)V", at = @At("HEAD"), cancellable = true, require = 0)
    private static void jjstools$hat(DrawContext context, Identifier texture, int x, int y, int size, boolean upsideDown, int color, CallbackInfo ci) {
        if (!TabOptimizer.renderingTab || !TabOptimizer.on()) return;
        if (!TabOptimizer.get().hatLayer.get()) ci.cancel();
    }

    /**
     * Head atlas: every face comes from one shared texture, so all heads can be drawn together.
     * The SkinTextures overloads end up here with the skin's texture id. If the face cannot be
     * read, nothing is cancelled and the head is drawn the normal way.
     */
    @Inject(method = "draw(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/util/Identifier;IIIZZI)V", at = @At("HEAD"), cancellable = true, require = 0)
    private static void jjstools$atlas(DrawContext context, Identifier texture, int x, int y, int size, boolean hatVisible, boolean upsideDown, int color, CallbackInfo ci) {
        if (!TabOptimizer.renderingTab || !TabOptimizer.on() || !TabOptimizer.get().headAtlas.get()) return;

        boolean hat = hatVisible && TabOptimizer.get().hatLayer.get();
        int faded = TabOptimizer.get().liteRenderer.get() ? dev.jjstools.tab.LiteTab.fade(color) : color;
        if (HeadAtlas.draw(context, texture, x, y, size, hat, faded)) ci.cancel();
    }

    /** True when this head should not be drawn at all. */
    @Unique
    private static boolean jjstools$blocked() {
        if (!TabOptimizer.renderingTab || !TabOptimizer.on()) return false;
        if (TabOptimizer.inHead) return false;

        TabOptimizer module = TabOptimizer.get();
        if (!module.heads.get()) return true;

        int limit = module.headLimit.get();
        if (limit > 0 && TabOptimizer.headsDrawn >= limit) return true;

        TabOptimizer.headsDrawn++;
        TabOptimizer.inHead = true;
        return false;
    }
}
