package dev.jjstools.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.jjstools.modules.TabOptimizer;
import dev.jjstools.tab.HeadAtlas;
import dev.jjstools.tab.LiteTab;
import dev.jjstools.tab.SkinCache;
import dev.jjstools.tab.TabHooks;
import dev.jjstools.tab.TabCache;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Comparator;
import java.util.List;

/**
 * Priority 1500 so this applies after Meteor's own PlayerListHudMixin: Better
 * Tab's accurate-latency cancels the ping icon first and wins.
 */
@Mixin(value = PlayerListHud.class, priority = 1500)
public abstract class PlayerListHudMixin implements TabHooks {
    @Shadow @Final private static Comparator<PlayerListEntry> ENTRY_ORDERING;

    @Shadow @Nullable private Text header;
    @Shadow @Nullable private Text footer;

    @Shadow protected abstract List<PlayerListEntry> collectPlayerEntries();

    @Shadow protected abstract void renderLatencyIcon(DrawContext context, int width, int x, int y, PlayerListEntry entry);

    @Override
    public void jjstools$renderLatency(DrawContext ctx, int width, int x, int y, PlayerListEntry entry) {
        renderLatencyIcon(ctx, width, x, y, entry);
    }

    /** Lite renderer: replaces the vanilla draw entirely. Runs inside the scale wrapper below. */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void jjstools$liteRender(DrawContext context, int scaledWindowWidth, Scoreboard scoreboard, ScoreboardObjective objective, CallbackInfo ci) {
        if (!TabOptimizer.on() || !TabOptimizer.get().liteRenderer.get()) return;

        LiteTab.render((PlayerListHud) (Object) this, context, scaledWindowWidth, collectPlayerEntries(), header, footer);
        ci.cancel();
    }

    /**
     * Builds the list itself instead of letting vanilla do it, so max-rendered is the only cap.
     * Vanilla stops at 80 players and Better Tab's tablist-size replaces that 80; neither applies
     * while Tablist Optimisations is on. Same sort order as vanilla.
     */
    @Inject(method = "collectPlayerEntries", at = @At("HEAD"), cancellable = true)
    private void jjstools$collect(CallbackInfoReturnable<List<PlayerListEntry>> cir) {
        if (!TabOptimizer.on()) return;

        List<PlayerListEntry> cached = TabCache.get(TabOptimizer.get().cacheTime.get());
        if (cached != null) {
            cir.setReturnValue(cached);
            return;
        }

        ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
        if (handler == null) return;

        List<PlayerListEntry> list = handler.getListedPlayerListEntries().stream()
            .sorted(ENTRY_ORDERING)
            .limit(TabOptimizer.get().maxRendered.get())
            .toList();

        TabCache.put(list);
        cir.setReturnValue(list);
    }

    /**
     * Wraps the whole render call, so the matrix push/pop stays balanced even if
     * another mod cancels render partway. Scale is applied to the finished list,
     * so text and its row background always share one transform. The transform
     * pins the horizontal centre and the top margin.
     */
    @WrapMethod(method = "render")
    private void jjstools$wrapRender(DrawContext context, int scaledWindowWidth, Scoreboard scoreboard, ScoreboardObjective objective, Operation<Void> original) {
        TabOptimizer.lastHookNanos = System.nanoTime();
        TabOptimizer.headsDrawn = 0;
        TabOptimizer.inHead = false;

        if (!TabOptimizer.on()) {
            original.call(context, scaledWindowWidth, scoreboard, objective);
            return;
        }

        // The lite renderer does its own scaling and positioning (auto-fit, anchor-to-screen).
        float s = TabOptimizer.get().liteRenderer.get() ? 1.0f : TabOptimizer.get().scale.get().floatValue();
        boolean pushed = false;
        TabOptimizer.renderingTab = true;

        try {
            if (s != 1.0f) {
                float centreX = scaledWindowWidth / 2.0f;
                float topMargin = 10.0f;

                context.getMatrices().pushMatrix();
                context.getMatrices().translate(centreX * (1.0f - s), topMargin * (1.0f - s));
                context.getMatrices().scale(s, s);
                pushed = true;
            }

            original.call(context, scaledWindowWidth, scoreboard, objective);
        } finally {
            TabOptimizer.renderingTab = false;
            TabOptimizer.inHead = false;
            if (pushed) context.getMatrices().popMatrix();
            HeadAtlas.flush();
        }
    }

    @WrapOperation(
        method = "render",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/PlayerListEntry;getSkinTextures()Lnet/minecraft/entity/player/SkinTextures;"),
        require = 0
    )
    private SkinTextures jjstools$cachedSkin(PlayerListEntry entry, Operation<SkinTextures> original) {
        if (!TabOptimizer.on() || !TabOptimizer.get().cacheSkins.get()) return original.call(entry);
        return SkinCache.get(entry, TabOptimizer.get().cacheTime.get(), e -> original.call(e));
    }

    @Inject(method = "renderLatencyIcon", at = @At("HEAD"), cancellable = true)
    private void jjstools$latencyIcon(DrawContext context, int width, int x, int y, PlayerListEntry entry, CallbackInfo ci) {
        if (TabOptimizer.on() && !TabOptimizer.get().pingIcons.get()) ci.cancel();
    }
}
