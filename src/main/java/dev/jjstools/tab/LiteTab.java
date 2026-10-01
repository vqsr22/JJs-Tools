package dev.jjstools.tab;

import dev.jjstools.modules.TabOptimizer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.BetterTab;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Lite tab list renderer.
 *
 * Names, colours, widths, wrapping and layout are worked out once per rebuild instead of every
 * frame. Backgrounds, heads and ping icons still go through the vanilla GUI. With meteor-text on,
 * the text does not: it is queued here and drawn afterwards in Meteor's Render2DEvent (which runs
 * after the vanilla GUI has been flushed) through Meteor's own text renderer. That renderer batches
 * every string into one mesh, and uses Meteor's custom font when Custom Font is on in Meteor's config.
 * The scoreboard objective column is not drawn in lite mode.
 */
public final class LiteTab {
    private LiteTab() {
    }

    private static final int ROW = 9;
    private static final int GAP = 5;
    private static final int TOP = 10;               // vanilla top margin
    private static final int EDGE = 2;               // gap kept from the screen edge when anchoring
    private static final int BOX = 0x80000000;       // vanilla header/footer box
    private static final int ENTRY_BG = 0x20FFFFFF;  // vanilla per-player fill
    private static final int WHITE = 0xFFFFFFFF;
    private static final int SPECTATOR = 0x90FFFFFF; // vanilla spectator alpha

    /** One line of text split into same-colour runs, with x offsets in GUI units. */
    private static final class Line {
        final List<String> parts = new ArrayList<>();
        final List<Integer> colors = new ArrayList<>();
        final List<Float> offsets = new ArrayList<>();
        float width;
    }

    // Snapshot key
    private static List<PlayerListEntry> keyEntries;
    private static Text keyHeader;
    private static Text keyFooter;
    private static int keyWidth = -1;
    private static int keyHeight = -1;
    private static float keyScale = -1f;
    private static int keyFlags = -1;
    private static Object keyFont;
    private static long builtAt;

    // Snapshot
    private static PlayerListEntry[] entries = new PlayerListEntry[0];
    private static Text[] names = new Text[0];
    private static Line[] nameLines = new Line[0];
    private static boolean[] spectator = new boolean[0];
    private static List<OrderedText> headerLines = List.of();
    private static List<OrderedText> footerLines = List.of();
    private static Line[] headerRuns = new Line[0];
    private static Line[] footerRuns = new Line[0];
    private static String[] headerPlain = new String[0];
    private static String[] footerPlain = new String[0];
    private static int[] headerWidths = new int[0];
    private static int[] footerWidths = new int[0];
    private static int rows;
    private static int cols;
    private static int entryW;
    private static int listW;
    private static int boxW;
    private static float drawScale = 1f;
    private static float originX;
    private static float originY;

    // Text queued for Render2DEvent
    private static final List<Line> queuedLines = new ArrayList<>();
    private static final List<float[]> queuedPos = new ArrayList<>();
    private static boolean queued;
    private static boolean queuedShadow;
    private static final Color COLOR = new Color();

    // Fade: goes up over fade-in ms while tab is held, down over fade-out ms after it is let go.
    private static long lastFadeNanos;
    private static float fadeAlpha = 0f;
    private static boolean held;

    /** Called every frame from InGameHudMixin, whether or not tab is held. */
    public static void tickFade(boolean tabHeld, int fadeInMs, int fadeOutMs) {
        long now = System.nanoTime();
        float dtMs = lastFadeNanos == 0 ? 0 : Math.min(100f, (now - lastFadeNanos) / 1_000_000f);
        lastFadeNanos = now;
        held = tabHeld;

        if (tabHeld) fadeAlpha = fadeInMs <= 0 ? 1f : Math.min(1f, fadeAlpha + dtMs / fadeInMs);
        else fadeAlpha = fadeOutMs <= 0 ? 0f : Math.max(0f, fadeAlpha - dtMs / fadeOutMs);
    }

    /** True while tab has been let go but the list is still fading out. */
    public static boolean fadingOut() {
        return !held && fadeAlpha > 0f;
    }

    /** Applies the current fade to an ARGB colour. */
    public static int fade(int argb) {
        if (fadeAlpha >= 1f) return argb;
        int a = Math.round(((argb >>> 24) & 0xFF) * fadeAlpha);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    public static void invalidate() {
        keyEntries = null;
        keyHeader = null;
        keyFooter = null;
        keyWidth = -1;
        keyHeight = -1;
        keyScale = -1f;
        keyFlags = -1;
        keyFont = null;
        queued = false;
        queuedLines.clear();
        queuedPos.clear();
    }

    public static void render(PlayerListHud hud, DrawContext ctx, int screenW, List<PlayerListEntry> list, Text header, Text footer) {
        TabOptimizer module = TabOptimizer.get();
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int screenH = ctx.getScaledWindowHeight();

        BetterTab betterTab = Modules.get() == null ? null : Modules.get().get(BetterTab.class);
        boolean bt = betterTab != null && betterTab.isActive();
        boolean accurate = bt && betterTab.accurateLatency.get();
        int fallbackRows = Math.max(1, bt ? betterTab.tabHeight.get() : 20);
        boolean heads = module.heads.get();
        boolean shadow = module.textShadow.get();
        boolean meteorText = module.meteorText.get();
        boolean namesOnly = meteorText && module.customFontNamesOnly.get();
        boolean meteorHeader = meteorText && !namesOnly;
        Object font = meteorText ? meteordevelopment.meteorclient.renderer.text.TextRenderer.get() : null;

        int height = module.height.get();
        boolean autoFit = module.autoFit.get();
        boolean anchor = module.anchor.get();
        float userScale = module.scale.get().floatValue();

        int flags = (heads ? 1 : 0) | (accurate ? 2 : 0) | (meteorText ? 4 : 0) | (namesOnly ? 8 : 0)
            | (autoFit ? 16 : 0) | (anchor ? 32 : 0) | (fallbackRows << 6) | (height << 16);
        int maxAge = module.cacheTime.get();
        boolean stale = list != keyEntries || header != keyHeader || footer != keyFooter
            || screenW != keyWidth || screenH != keyHeight || userScale != keyScale
            || flags != keyFlags || font != keyFont
            || maxAge <= 0 || System.currentTimeMillis() - builtAt > maxAge;

        if (stale) {
            rebuild(hud, tr, list, header, footer, heads, accurate, meteorText, meteorHeader,
                screenW, screenH, height > 0 ? height : fallbackRows, height <= 0 && autoFit, autoFit, anchor, userScale);
            keyEntries = list;
            keyHeader = header;
            keyFooter = footer;
            keyWidth = screenW;
            keyHeight = screenH;
            keyScale = userScale;
            keyFlags = flags;
            keyFont = font;
            builtAt = System.currentTimeMillis();
        }

        queuedLines.clear();
        queuedPos.clear();
        queued = false;

        // Everything below is drawn in local coordinates, (0, 0) = top-left of the box.
        ctx.getMatrices().pushMatrix();
        try {
            ctx.getMatrices().translate(originX, originY);
            ctx.getMatrices().scale(drawScale, drawScale);
            draw(hud, ctx, tr, module, heads, shadow, meteorText, meteorHeader, maxAge);
        } finally {
            ctx.getMatrices().popMatrix();
        }

        if (meteorText) {
            queued = !queuedLines.isEmpty();
            queuedShadow = shadow;
        }
    }

    private static void draw(PlayerListHud hud, DrawContext ctx, TextRenderer tr, TabOptimizer module,
                             boolean heads, boolean shadow, boolean meteorText, boolean meteorHeader, int maxAge) {
        int n = entries.length;
        int centre = boxW / 2;
        int headerH = headerLines.isEmpty() ? 0 : headerLines.size() * ROW + 1;
        int listTop = headerH;
        int listLeft = (boxW - listW) / 2;

        ctx.fill(-1, -1, boxW + 1, listTop + rows * ROW, fade(BOX));

        for (int i = 0; i < headerLines.size(); i++) {
            int x = centre - headerWidths[i] / 2;
            int y = i * ROW;
            int override = headerOverride(module, i);
            if (override != 0) {
                if (meteorHeader) queue(solid(headerPlain[i], override), x, y);
                else ctx.drawText(tr, headerPlain[i], x, y, fade(override), shadow);
            } else if (meteorHeader) queue(headerRuns[i], x, y);
            else ctx.drawText(tr, headerLines.get(i), x, y, fade(-1), shadow);
        }

        for (int c = 0; c < cols; c++) {
            int inCol = Math.min(rows, n - c * rows);
            if (inCol <= 0) break;
            int x = listLeft + c * (entryW + GAP);
            ctx.fill(x, listTop, x + entryW, listTop + inCol * ROW - 1, fade(ENTRY_BG));
        }

        // Separate passes, so everything that shares a texture is drawn back to back and can be
        // batched: all heads (one texture with head-atlas), then names, then all ping icons.
        int textOffset = heads ? 9 : 0;
        if (heads) {
            boolean cache = module.cacheSkins.get();
            for (int i = 0; i < n; i++) {
                PlayerListEntry entry = entries[i];
                SkinTextures skin = cache ? SkinCache.get(entry, maxAge, PlayerListEntry::getSkinTextures) : entry.getSkinTextures();
                PlayerSkinDrawer.draw(ctx, skin, listLeft + colX(i), rowY(i, listTop), 8);
            }
        }

        for (int i = 0; i < n; i++) {
            int x = listLeft + colX(i) + textOffset;
            int y = rowY(i, listTop);
            if (meteorText) queue(nameLines[i], x, y);
            else ctx.drawText(tr, names[i], x, y, fade(spectator[i] ? SPECTATOR : -1), shadow);
        }

        for (int i = 0; i < n; i++) {
            TabHooks.latency(hud, ctx, entryW, listLeft + colX(i), rowY(i, listTop), entries[i]);
        }

        if (!footerLines.isEmpty()) {
            int fy = listTop + rows * ROW + 1;
            ctx.fill(-1, fy - 1, boxW + 1, fy + footerLines.size() * ROW, fade(BOX));
            for (int i = 0; i < footerLines.size(); i++) {
                int x = centre - footerWidths[i] / 2;
                int y = fy + i * ROW;
                int override = module.recolourFooter.get() ? module.footerColour.get().getPacked() : 0;
                if (override != 0) {
                    if (meteorHeader) queue(solid(footerPlain[i], override), x, y);
                    else ctx.drawText(tr, footerPlain[i], x, y, fade(override), shadow);
                } else if (meteorHeader) queue(footerRuns[i], x, y);
                else ctx.drawText(tr, footerLines.get(i), x, y, fade(-1), shadow);
            }
        }
    }

    /** Recolour for a header line, or 0 for none. Line 0 is the title, the rest are the server message. */
    private static int headerOverride(TabOptimizer module, int line) {
        if (line == 0) return module.recolourTitle.get() ? module.titleColour.get().getPacked() : 0;
        return module.recolourMessage.get() ? module.messageColour.get().getPacked() : 0;
    }

    /** One run of text in one colour, for Meteor's renderer. */
    private static Line solid(String text, int argb) {
        Line line = new Line();
        line.parts.add(text);
        line.colors.add(argb);
        line.offsets.add(0f);
        return line;
    }

    private static String plain(OrderedText text) {
        StringBuilder sb = new StringBuilder();
        text.accept((index, style, codePoint) -> {
            sb.appendCodePoint(codePoint);
            return true;
        });
        return sb.toString();
    }

    private static int colX(int i) {
        return (i / rows) * (entryW + GAP);
    }

    private static int rowY(int i, int listTop) {
        return listTop + (i % rows) * ROW;
    }

    /** Called from TabOptimizer's Render2DEvent handler, after the vanilla GUI is on screen. */
    public static void drawQueuedText() {
        if (!queued) return;
        queued = false;

        MinecraftClient mc = MinecraftClient.getInstance();
        double gs = mc.getWindow().getScaleFactor();
        double s = drawScale;

        meteordevelopment.meteorclient.renderer.text.TextRenderer text = meteordevelopment.meteorclient.renderer.text.TextRenderer.get();
        text.begin(gs * s / 2.0);
        for (int j = 0; j < queuedLines.size(); j++) {
            Line line = queuedLines.get(j);
            float[] pos = queuedPos.get(j);
            double py = (originY + pos[1] * s) * gs;
            for (int p = 0; p < line.parts.size(); p++) {
                double px = (originX + (pos[0] + line.offsets.get(p)) * s) * gs;
                setColor(fade(line.colors.get(p)));
                text.render(line.parts.get(p), px, py, COLOR, queuedShadow);
            }
        }
        text.end();

        queuedLines.clear();
        queuedPos.clear();
    }

    private static void queue(Line line, int x, int y) {
        if (line == null || line.parts.isEmpty()) return;
        queuedLines.add(line);
        queuedPos.add(new float[]{x, y});
    }

    private static void rebuild(PlayerListHud hud, TextRenderer tr, List<PlayerListEntry> list, Text header, Text footer,
                                boolean heads, boolean accurate, boolean meteorText, boolean meteorHeader,
                                int screenW, int screenH, int fixedRows, boolean autoRows, boolean autoFit,
                                boolean anchor, float userScale) {
        int n = list.size();
        entries = list.toArray(new PlayerListEntry[0]);
        names = new Text[n];
        nameLines = new Line[n];
        spectator = new boolean[n];

        int maxName = 0;
        for (int i = 0; i < n; i++) {
            PlayerListEntry e = entries[i];
            names[i] = hud.getPlayerName(e); // goes through Better Tab's friend/self colours
            spectator[i] = e.getGameMode() == GameMode.SPECTATOR;
            if (meteorText) {
                nameLines[i] = runs(names[i], spectator[i] ? SPECTATOR : WHITE);
                maxName = Math.max(maxName, (int) Math.ceil(nameLines[i].width));
            } else {
                maxName = Math.max(maxName, tr.getWidth(names[i]));
            }
        }

        // Every column is wide enough for the longest name, so names never run into the next column.
        entryW = (heads ? 9 : 0) + maxName + 2 + (accurate ? 34 : 12);

        int wrap = Math.max(50, screenW - 50);
        headerLines = header == null ? List.of() : tr.wrapLines(header, wrap);
        footerLines = footer == null ? List.of() : tr.wrapLines(footer, wrap);
        headerRuns = new Line[headerLines.size()];
        footerRuns = new Line[footerLines.size()];
        headerWidths = new int[headerLines.size()];
        footerWidths = new int[footerLines.size()];
        headerPlain = new String[headerLines.size()];
        footerPlain = new String[footerLines.size()];
        for (int i = 0; i < headerPlain.length; i++) headerPlain[i] = plain(headerLines.get(i));
        for (int i = 0; i < footerPlain.length; i++) footerPlain[i] = plain(footerLines.get(i));
        int textW = 0;
        for (int i = 0; i < headerLines.size(); i++) {
            if (meteorHeader) {
                headerRuns[i] = runs(headerLines.get(i));
                headerWidths[i] = (int) Math.ceil(headerRuns[i].width);
            } else {
                headerWidths[i] = tr.getWidth(headerLines.get(i));
            }
            textW = Math.max(textW, headerWidths[i]);
        }
        for (int i = 0; i < footerLines.size(); i++) {
            if (meteorHeader) {
                footerRuns[i] = runs(footerLines.get(i));
                footerWidths[i] = (int) Math.ceil(footerRuns[i].width);
            } else {
                footerWidths[i] = tr.getWidth(footerLines.get(i));
            }
            textW = Math.max(textW, footerWidths[i]);
        }

        int extraH = (headerLines.isEmpty() ? 0 : headerLines.size() * ROW + 1)
            + (footerLines.isEmpty() ? 0 : footerLines.size() * ROW + 1) + 2;
        int availW = screenW - 2 * EDGE;
        int availH = screenH - TOP - EDGE;

        // Rows per column: fixed, or the height that lets the list be drawn biggest while fitting.
        if (autoRows && n > 0) {
            int bestRows = 1;
            double bestFit = -1;
            for (int r = 1; r <= n; r++) {
                int c = (n + r - 1) / r;
                if (r > 1 && (n + r - 2) / (r - 1) == c) continue; // same column count as r-1, only taller
                double fit = fitScale(c * entryW + (c - 1) * GAP, textW, r * ROW + extraH, availW, availH);
                if (fit > bestFit) {
                    bestFit = fit;
                    bestRows = r;
                }
            }
            rows = bestRows;
        } else {
            rows = Math.max(1, Math.min(fixedRows, Math.max(n, 1)));
        }
        cols = Math.max(1, (n + rows - 1) / rows);
        listW = cols * entryW + (cols - 1) * GAP;
        boxW = Math.max(listW, textW);
        int boxH = rows * ROW + extraH;

        // Scale: yours, shrunk if needed so it fits the screen.
        drawScale = userScale;
        if (autoFit) drawScale = (float) Math.min(userScale, fitScale(listW, textW, boxH - 2, availW, availH));

        // Position: centred at the top like vanilla, pushed back inside the screen if it would run off.
        float w = (boxW + 2) * drawScale;
        float h = boxH * drawScale;
        originX = screenW / 2.0f - w / 2.0f + drawScale;
        originY = TOP;
        if (anchor) {
            originX = clamp(originX, EDGE + drawScale, screenW - EDGE - w + drawScale);
            originY = clamp(originY, EDGE + drawScale, screenH - EDGE - h + drawScale);
        }
    }

    private static double fitScale(int listW, int textW, int boxH, int availW, int availH) {
        int w = Math.max(listW, textW) + 2;
        return Math.min(availW / (double) w, availH / (double) Math.max(1, boxH));
    }

    /** Like Math.clamp, but if the list is bigger than the screen it sticks to the top/left edge. */
    private static float clamp(float v, float min, float max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, v));
    }

    // Splitting text into colour runs for Meteor's renderer

    private static Line runs(Text text, int base) {
        Line line = new Line();
        StringBuilder sb = new StringBuilder();
        int[] current = {Integer.MIN_VALUE};

        text.visit((style, str) -> {
            int c = colorOf(style, base);
            if (c != current[0] && sb.length() > 0) {
                flush(line, sb, current[0]);
            }
            current[0] = c;
            sb.append(str);
            return Optional.empty();
        }, Style.EMPTY);

        if (sb.length() > 0) flush(line, sb, current[0]);
        return line;
    }

    private static Line runs(OrderedText text) {
        Line line = new Line();
        StringBuilder sb = new StringBuilder();
        int[] current = {Integer.MIN_VALUE};

        text.accept((index, style, codePoint) -> {
            int c = colorOf(style, WHITE);
            if (c != current[0] && sb.length() > 0) {
                flush(line, sb, current[0]);
            }
            current[0] = c;
            sb.appendCodePoint(codePoint);
            return true;
        });

        if (sb.length() > 0) flush(line, sb, current[0]);
        return line;
    }

    private static void flush(Line line, StringBuilder sb, int color) {
        String s = sb.toString();
        sb.setLength(0);
        line.parts.add(s);
        line.colors.add(color);
        line.offsets.add(line.width);
        // Meteor's renderers report width at their base scale, which is 2x GUI units.
        line.width += (float) (meteordevelopment.meteorclient.renderer.text.TextRenderer.get().getWidth(s) / 2.0);
    }

    private static int colorOf(Style style, int base) {
        TextColor color = style.getColor();
        if (color == null) return base;
        return (base & 0xFF000000) | (color.getRgb() & 0xFFFFFF);
    }

    private static void setColor(int argb) {
        COLOR.a = (argb >>> 24) & 0xFF;
        COLOR.r = (argb >> 16) & 0xFF;
        COLOR.g = (argb >> 8) & 0xFF;
        COLOR.b = argb & 0xFF;
    }
}
