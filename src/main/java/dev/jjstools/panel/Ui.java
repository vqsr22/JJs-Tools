package dev.jjstools.panel;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.item.ItemStack;
import net.minecraft.resource.Resource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

/**
 * The Control Panel's drawing kit.
 *
 * Flat and plain: panels, lists, buttons and boxes are simple rectangles with a hairline edge.
 * The colours come from your resource pack's inventory screen, read off its texture when the
 * panel opens, so the panel sits in with the rest of your GUI whatever pack you use. Light mode
 * uses the pack's colours as they are; dark mode shades them well down. Text colours are picked
 * to stand out from whichever background they are on.
 *
 * Works the way a sketch does rather than with widget objects: every frame each panel draws
 * itself and says, as it goes, which rectangles do what when clicked, scrolled or dragged. The
 * screen keeps those for the frame and answers clicks from them, topmost first. That keeps each
 * panel one straightforward method that lays itself out for the size it has been given.
 */
public final class Ui {
    public static final Identifier INVENTORY = Identifier.ofVanilla("textures/gui/container/inventory.png");

    public static final int GREEN = 0xFF4CD964;
    public static final int GREY_DOT = 0xFF7A7A7A;
    public static final int RED = 0xFFFF5A5A;
    /** Arrows for which way a list is sorted: up for ascending, down for descending. */
    public static final String UP = "\u25B2";
    public static final String DOWN = "\u25BC";
    public static final int GOLD = 0xFFFFC94D;

    /**
     * How the panel is coloured. Light and Dark take the main colour from your resource pack (or
     * the colour set in the module); the rest are fixed palettes: plain white, the GMK Olivia
     * keycaps' pinks and charcoal either way round, GMK Samurai's charcoal and red, GMK Striker's
     * navy with white, Spotify's black and green, and Discord's greys and blurple.
     */
    public enum Theme {
        Dark("Dark", 0, 0, 0, 0, 0),
        Light("Light", 0, 0, 0, 0, 0),
        White("White", 0xFFFFFFFF, 0xFFF3F4F6, 0xFFE5E7EB, 0xFFE9EAEE, 0xFF2F6FED),
        Olivia("Olivia", 0xFFEAD3CC, 0xFFF7EAE6, 0xFF383535, 0xFFE2C0B6, 0xFFC97B6C),
        OliviaDark("Olivia Dark", 0xFF2E2B2B, 0xFF242222, 0xFF433E3E, 0xFF3A3535, 0xFFE8C4B8),
        Samurai("Samurai", 0xFF2B2B2F, 0xFF222225, 0xFF3A3A3F, 0xFF323236, 0xFFC8352E),
        Striker("Striker", 0xFF1D3561, 0xFF152849, 0xFF27457A, 0xFF22406F, 0xFFFFFFFF),
        Spotify("Spotify", 0xFF121212, 0xFF181818, 0xFF282828, 0xFF242424, 0xFF1DB954),
        Discord("Discord", 0xFF313338, 0xFF2B2D31, 0xFF4E5058, 0xFF1E1F22, 0xFF5865F2);

        public final String title;
        final int panel, well, button, slot, accent;

        Theme(String title, int panel, int well, int button, int slot, int accent) {
            this.title = title;
            this.panel = panel;
            this.well = well;
            this.button = button;
            this.slot = slot;
            this.accent = accent;
        }

        boolean fixed() {
            return this != Light && this != Dark;
        }

        public static Theme of(String name) {
            for (Theme t : values()) if (t.name().equalsIgnoreCase(name)) return t;
            return Dark;
        }
    }

    /** Something being dragged, told where the mouse is each frame until the button comes up. */
    public interface Drag {
        void move(int mx, int my);
    }

    private record Hit(int x1, int y1, int x2, int y2, int button, IntConsumer click, DoubleConsumer scroll) {}

    public final MinecraftClient mc = MinecraftClient.getInstance();
    public DrawContext ctx;
    public TextRenderer tr;
    public float scale = 1.0F;
    public Theme theme = Theme.Dark;
    /** Whether the backgrounds are dark, for the few things drawn in their own colours. */
    public boolean dark;
    public int mx = -1, my = -1;
    public long now;

    private final List<Hit> hits = new ArrayList<>();
    private final Deque<int[]> clips = new ArrayDeque<>();
    private List<Text> tooltip;
    public TextBox focus;
    public Drag drag;

    /** The pack's own colours, read off its inventory texture: the panel and the inside of a slot. */
    private static int packPanel = 0xFFC6C6C6;
    private static int packSlot = 0xFF8B8B8B;

    public void begin(DrawContext ctx, int mx, int my, float scale, Theme theme) {
        this.ctx = ctx;
        this.tr = mc.textRenderer;
        this.scale = scale;
        this.theme = theme;
        this.dark = theme == Theme.Dark || theme.fixed() && !light(theme.panel);
        this.mx = mx;
        this.my = my;
        this.now = System.currentTimeMillis();
        hits.clear();
        clips.clear();
        tooltip = null;
    }

    /** Looks at the resource pack's inventory texture for its colours. Done when the panel opens. */
    public static void readPack() {
        MinecraftClient mc = MinecraftClient.getInstance();
        try {
            Optional<Resource> resource = mc.getResourceManager().getResource(INVENTORY);
            if (resource.isEmpty()) return;
            try (InputStream in = resource.get().getInputStream(); NativeImage image = NativeImage.read(in)) {
                int s = Math.max(1, image.getWidth() / 256);
                // Plain background beside the crafting grid, and the inside of the first armour slot.
                packPanel = 0xFF000000 | image.getColorArgb(165 * s, 8 * s);
                packSlot = 0xFF000000 | image.getColorArgb(12 * s, 12 * s);
            }
        } catch (Exception ignored) {
            // Vanilla's colours stay.
        }
    }

    // Colours

    private static int shade(int argb, float f) {
        int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * f));
        int g = Math.min(255, Math.round(((argb >> 8) & 0xFF) * f));
        int b = Math.min(255, Math.round((argb & 0xFF) * f));
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static double luminance(int argb) {
        return (0.299 * ((argb >> 16) & 0xFF) + 0.587 * ((argb >> 8) & 0xFF) + 0.114 * (argb & 0xFF)) / 255.0;
    }

    private static boolean light(int argb) {
        return luminance(argb) > 0.5;
    }

    /** The main colour: the one chosen in the module's settings, or the pack's inventory colour. */
    private static int base() {
        Integer chosen = dev.jjstools.modules.ControlPanel.mainColour();
        return chosen != null ? chosen : packPanel;
    }

    /** The inside of a slot: the pack's, or a darker shade of a chosen main colour. */
    private static int slotBase() {
        Integer chosen = dev.jjstools.modules.ControlPanel.mainColour();
        return chosen != null ? shade(chosen, 0.7F) : packSlot;
    }

    public int panelColour() {
        if (theme.fixed()) return theme.panel;
        return theme == Theme.Dark ? shade(base(), 0.22F) : base();
    }

    public int wellColour() {
        if (theme.fixed()) return theme.well;
        return theme == Theme.Dark ? shade(slotBase(), 0.16F) : mix(slotBase(), base(), 0.35F);
    }

    private int edge() {
        return light(panelColour()) ? 0x40000000 : 0x30FFFFFF;
    }

    private int buttonColour() {
        if (theme.fixed()) return theme.button;
        return theme == Theme.Dark ? shade(base(), 0.32F) : mix(base(), slotBase(), 0.45F);
    }

    /** Text on the panel itself. */
    public int fg() {
        return light(panelColour()) ? 0xFF303030 : 0xFFEDEDED;
    }

    public int fgDim() {
        return light(panelColour()) ? 0xFF6A6A6A : 0xFF9A9A9A;
    }

    /** Text inside a list, a box or a well. */
    public int fgWell() {
        return light(wellColour()) ? 0xFF262626 : 0xFFF0F0F0;
    }

    public int fgWellDim() {
        return light(wellColour()) ? 0xFF5A5A5A : 0xFF969696;
    }

    /** The accent colour from the module's settings, darkened a little on light backgrounds so it reads. */
    public int accent() {
        if (theme.fixed()) return theme.accent;
        int a = dev.jjstools.modules.ControlPanel.accent();
        return light(panelColour()) && light(a) ? shade(a, 0.6F) : a;
    }

    public int rowHover() {
        return light(wellColour()) ? 0x18000000 : 0x14FFFFFF;
    }

    public int rowSelected() {
        return light(wellColour()) ? 0x30000000 : 0x26FFFFFF;
    }

    // Text

    public int lineH() {
        return Math.round(tr.fontHeight * scale);
    }

    public int width(String s) {
        return Math.round(tr.getWidth(s) * scale);
    }

    public void text(String s, int x, int y, int colour) {
        if (s == null || s.isEmpty()) return;
        if (scale == 1.0F) {
            ctx.drawText(tr, s, x, y, colour, false);
            return;
        }
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(x, y);
        ctx.getMatrices().scale(scale, scale);
        ctx.drawText(tr, s, 0, 0, colour, false);
        ctx.getMatrices().popMatrix();
    }

    public void textRight(String s, int right, int y, int colour) {
        text(s, right - width(s), y, colour);
    }

    public void textCentred(String s, int centre, int y, int colour) {
        text(s, centre - width(s) / 2, y, colour);
    }

    /**
     * A message centred in an area, wrapped onto as many lines as it needs to stay inside it,
     * as for an empty list.
     */
    public void centredWrapped(String s, int x, int w, int centreY, int colour) {
        List<String> lines = wrap(s, Math.max(20, w - 12));
        int step = lineH() + 2;
        int y = centreY - lines.size() * step / 2;
        for (String line : lines) {
            textCentred(line, x + w / 2, y, colour);
            y += step;
        }
    }

    /** Smaller text, for details under the main line. */
    public void small(String s, int x, int y, int colour) {
        float old = scale;
        scale = old * 0.75F;
        text(s, x, y, colour);
        scale = old;
    }

    public int smallH() {
        return Math.round(tr.fontHeight * scale * 0.75F);
    }

    public int smallWidth(String s) {
        return Math.round(tr.getWidth(s) * scale * 0.75F);
    }

    public String fitSmall(String s, int maxW) {
        float old = scale;
        scale = old * 0.75F;
        String r = fit(s, maxW);
        scale = old;
        return r;
    }

    /** Cuts a string down to fit, ending it with dots. */
    public String fit(String s, int maxW) {
        if (s == null) return "";
        if (width(s) <= maxW) return s;
        String dots = "...";
        int dotsW = width(dots);
        int end = s.length();
        while (end > 0 && width(s.substring(0, end)) + dotsW > maxW) end--;
        return s.substring(0, end) + dots;
    }

    /** Splits text into lines no wider than maxW, breaking between words where it can. */
    public List<String> wrap(String s, int maxW) {
        List<String> lines = new ArrayList<>();
        if (s == null || s.isEmpty()) {
            lines.add("");
            return lines;
        }
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ", -1)) {
            String attempt = line.isEmpty() ? word : line + " " + word;
            if (width(attempt) <= maxW) {
                line.setLength(0);
                line.append(attempt);
                continue;
            }
            if (!line.isEmpty()) lines.add(line.toString());
            line.setLength(0);
            String rest = word;
            while (width(rest) > maxW && rest.length() > 1) {
                int end = rest.length();
                while (end > 1 && width(rest.substring(0, end)) > maxW) end--;
                lines.add(rest.substring(0, end));
                rest = rest.substring(end);
            }
            line.append(rest);
        }
        lines.add(line.toString());
        return lines;
    }

    // Shapes

    public void fill(int x, int y, int w, int h, int argb) {
        if (w <= 0 || h <= 0) return;
        ctx.fill(x, y, x + w, y + h, argb);
    }

    private void outline(int x, int y, int w, int h, int argb) {
        fill(x, y, w, 1, argb);
        fill(x, y + h - 1, w, 1, argb);
        fill(x, y + 1, 1, h - 2, argb);
        fill(x + w - 1, y + 1, 1, h - 2, argb);
    }

    /** A small square, such as the online light. */
    public void dot(int x, int y, int size, int argb) {
        fill(x, y, size, size, argb);
    }

    /** A panel: the pack's inventory colour with a hairline edge. */
    public void panel(int x, int y, int w, int h) {
        fill(x, y, w, h, panelColour());
        outline(x, y, w, h, edge());
    }

    /** An area for lists and grids, a shade off the panel. */
    public void well(int x, int y, int w, int h) {
        fill(x, y, w, h, wellColour());
    }

    /** A thin line across, to separate things. */
    public void divider(int x, int y, int w) {
        fill(x, y, w, 1, edge());
    }

    /** An inventory slot in the pack's slot colour. */
    public void slot(int x, int y) {
        int base = theme.fixed() ? theme.slot : theme == Theme.Dark ? shade(slotBase(), 0.30F) : slotBase();
        fill(x, y, 18, 18, base);
        fill(x, y, 18, 1, shade(base, 0.7F));
        fill(x, y, 1, 18, shade(base, 0.7F));
    }

    public void item(ItemStack stack, int x, int y) {
        ctx.drawItem(stack, x, y);
    }

    /**
     * An item's number in the corner of its slot, drawn small enough to stay inside the slot
     * however big it is, so a grid of big counts does not run together.
     */
    public void itemCount(ItemStack stack, int x, int y, String count) {
        float s = count.length() <= 2 ? 1.0F : count.length() == 3 ? 0.75F : 0.6F;
        int w = Math.round(tr.getWidth(count) * s);
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(x + 17 - w, y + 17 - Math.round(8 * s));
        ctx.getMatrices().scale(s, s);
        ctx.drawTextWithShadow(tr, count, 0, 0, 0xFFFFFFFF);
        ctx.getMatrices().popMatrix();
    }

    /** Whether a player is on the server now, going by the player list. */
    public boolean online(String name) {
        return name != null && mc.getNetworkHandler() != null && mc.getNetworkHandler().getPlayerListEntry(name) != null;
    }

    /** A player's face: their skin if they are on, otherwise a tile with their first letter. */
    public void head(String name, int x, int y, int size) {
        PlayerListEntry entry = name == null || mc.getNetworkHandler() == null ? null : mc.getNetworkHandler().getPlayerListEntry(name);
        if (entry != null) {
            PlayerSkinDrawer.draw(ctx, entry.getSkinTextures(), x, y, size);
            return;
        }
        int hash = name == null ? 0 : name.toLowerCase(java.util.Locale.ROOT).hashCode();
        int colour = 0xFF000000 | (0x404040 + ((hash & 0x7F) << 16) + (((hash >> 7) & 0x7F) << 8) + ((hash >> 14) & 0x7F));
        fill(x, y, size, size, colour);
        String letter = name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        ctx.drawTextWithShadow(tr, letter, x + (size - tr.getWidth(letter)) / 2, y + (size - 8) / 2, 0xFFFFFFFF);
    }

    // Interaction

    /** Says that clicking in this rectangle does something; button is the mouse button it answers, or -1 for any. */
    public void onClick(int x, int y, int w, int h, int button, IntConsumer action) {
        int[] r = clipped(x, y, w, h);
        if (r == null) return;
        hits.add(new Hit(r[0], r[1], r[2], r[3], button, action, null));
    }

    public void onClick(int x, int y, int w, int h, Runnable action) {
        onClick(x, y, w, h, 0, b -> action.run());
    }

    public void onScroll(int x, int y, int w, int h, DoubleConsumer action) {
        int[] r = clipped(x, y, w, h);
        if (r == null) return;
        hits.add(new Hit(r[0], r[1], r[2], r[3], -2, null, action));
    }

    /** A strip that can be dragged, such as the edge between two columns. */
    public void dragHandle(int x, int y, int w, int h, Drag action) {
        boolean hover = hovered(x, y, w, h) || drag == action;
        if (hover) fill(x + w / 2, y, 1, h, accent());
        onClick(x, y, w, h, 0, b -> drag = action);
    }

    public boolean hovered(int x, int y, int w, int h) {
        if (mx < x || my < y || mx >= x + w || my >= y + h) return false;
        int[] clip = clips.peek();
        return clip == null || (mx >= clip[0] && my >= clip[1] && mx < clip[2] && my < clip[3]);
    }

    private int[] clipped(int x, int y, int w, int h) {
        int x1 = x, y1 = y, x2 = x + w, y2 = y + h;
        int[] clip = clips.peek();
        if (clip != null) {
            x1 = Math.max(x1, clip[0]);
            y1 = Math.max(y1, clip[1]);
            x2 = Math.min(x2, clip[2]);
            y2 = Math.min(y2, clip[3]);
        }
        return x2 > x1 && y2 > y1 ? new int[] { x1, y1, x2, y2 } : null;
    }

    /** Keeps drawing and clicks inside a rectangle until unclip. */
    public void clip(int x, int y, int w, int h) {
        int[] r = clipped(x, y, w, h);
        if (r == null) r = new int[] { x, y, x, y };
        clips.push(r);
        ctx.enableScissor(r[0], r[1], r[2], r[3]);
    }

    /** The game keeps its own stack of clips, so ending one goes back to the one outside it. */
    public void unclip() {
        clips.pop();
        ctx.disableScissor();
    }

    public void tooltip(List<Text> lines) {
        tooltip = lines;
        tooltipStack = null;
    }

    /**
     * A tooltip for an item. A shulker box or ender chest is shown the way Storage GUI Tweaks
     * shows it in an inventory, with what is inside, when that module is on; anything else gets
     * the lines given.
     */
    public void itemTooltip(ItemStack stack, List<Text> lines) {
        tooltip = lines;
        tooltipStack = stack;
    }

    private ItemStack tooltipStack;

    public void tooltip(String line) {
        tooltip = List.of(Text.literal(line));
        tooltipStack = null;
    }

    /** Draws the tooltip asked for this frame, if any, last so it is on top. */
    public void end() {
        if (tooltip == null || mx < 0) return;
        if (tooltipStack != null && dev.jjstools.modules.StorageGuiTweaks.inventoryPreview(ctx, tooltipStack, mx, my)) return;
        ctx.drawTooltip(tr, tooltip, mx, my);
    }

    boolean click(double x, double y, int button) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.click() == null || x < h.x1() || y < h.y1() || x >= h.x2() || y >= h.y2()) continue;
            if (h.button() >= 0 && h.button() != button) continue;
            h.click().accept(button);
            return true;
        }
        return false;
    }

    boolean scroll(double x, double y, double amount) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.scroll() == null || x < h.x1() || y < h.y1() || x >= h.x2() || y >= h.y2()) continue;
            h.scroll().accept(amount);
            return true;
        }
        return false;
    }

    // Widgets

    /** A flat button. Returns true while hovered. */
    public boolean button(int x, int y, int w, int h, String label, boolean enabled, Runnable action) {
        boolean hover = enabled && hovered(x, y, w, h);
        int base = buttonColour();
        int colour = !enabled ? shade(base, 0.85F) : hover ? mix(base, 0xFFFFFFFF, dark ? 0.12F : 0.25F) : base;
        fill(x, y, w, h, colour);
        int text = !enabled ? (light(colour) ? 0xFF8A8A8A : 0xFF707070) : light(colour) ? 0xFF262626 : 0xFFF0F0F0;
        textCentred(fit(label, w - 6), x + w / 2, y + (h - lineH()) / 2 + 1, text);
        if (enabled && action != null) onClick(x, y, w, h, action);
        return hover;
    }

    public boolean button(int x, int y, int w, int h, String label, Runnable action) {
        return button(x, y, w, h, label, true, action);
    }

    /** A small red square that closes something. */
    public boolean closeButton(int x, int y, int size, Runnable action) {
        boolean hover = hovered(x, y, size, size);
        fill(x, y, size, size, hover ? 0xFFFF6B63 : 0xFFE0443C);
        if (action != null) onClick(x, y, size, size, action);
        return hover;
    }

    /** A button that shows when it is on, with a line under it, as for tabs and toggles. */
    public boolean toggle(int x, int y, int w, int h, String label, boolean on, Runnable action) {
        boolean hover = hovered(x, y, w, h);
        if (on || hover) fill(x, y, w, h, on ? rowSelected() : rowHover());
        if (on) fill(x, y + h - 1, w, 1, accent());
        textCentred(fit(label, w - 6), x + w / 2, y + (h - lineH()) / 2 + 1, on ? fg() : fgDim());
        if (action != null) onClick(x, y, w, h, action);
        return hover;
    }

    /** A square button with an item for its picture, as the creative menu's tabs have. */
    public boolean iconButton(int x, int y, int size, ItemStack icon, boolean on, String tip, Runnable action) {
        boolean hover = hovered(x, y, size, size);
        // A button's own background, so it reads as something to press.
        int base = buttonColour();
        fill(x, y, size, size, on ? mix(base, accent(), 0.25F) : hover ? mix(base, 0xFFFFFFFF, dark ? 0.12F : 0.25F) : base);
        item(icon, x + (size - 16) / 2, y + (size - 16) / 2);
        if (on) fill(x, y + size - 1, size, 1, accent());
        if (hover && tip != null) tooltip(tip);
        if (action != null) onClick(x, y, size, size, action);
        return hover;
    }

    /** A text box. Clicking focuses it, right-clicking empties it; typing goes to whichever has focus. */
    public void textBox(TextBox box, int x, int y, int w, int h) {
        boolean focused = focus == box;
        // A shade darker than the lists, so a box to type in stands out from what it searches.
        fill(x, y, w, h, shade(wellColour(), dark ? 0.7F : 0.9F));
        fill(x, y + h - 1, w, 1, focused ? accent() : edge());

        int pad = 4;
        int ty = y + (h - lineH()) / 2 + 1;
        clip(x + 1, y, w - 2, h);
        if (box.text.isEmpty() && !focused) {
            text(box.placeholder, x + pad, ty, fgWellDim());
        } else {
            // Shown from far enough along that the cursor is always in view.
            String shown = box.text;
            int room = w - pad * 2 - 4;
            int start = 0;
            while (start < box.cursor && width(shown.substring(start, box.cursor)) > room) start++;
            text(shown.substring(start), x + pad, ty, fgWell());
            if (focused && (now / 500) % 2 == 0) {
                int cx = x + pad + width(shown.substring(start, Math.max(start, box.cursor)));
                fill(cx, ty - 1, 1, lineH() + 1, fgWell());
            }
        }
        unclip();
        onClick(x, y, w, h, 0, b -> focus = box);
        onClick(x, y, w, h, 1, b -> {
            box.set("");
            focus = box;
        });
    }

    /**
     * A scrollable area. Returns the scroll offset to draw the contents at; the panel keeps it in
     * scroll[0] and says how tall its contents are, so it can be kept in range and a bar drawn.
     */
    public int scrollArea(int x, int y, int w, int h, int contentH, double[] scroll, int step) {
        int max = Math.max(0, contentH - h);
        scroll[0] = Math.max(0, Math.min(scroll[0], max));
        onScroll(x, y, w, h, amount -> scroll[0] = Math.max(0, Math.min(max, scroll[0] - amount * step)));

        if (max > 0) {
            int knobH = Math.max(12, h * h / contentH);
            int knobY = y + (int) ((h - knobH) * (scroll[0] / max));
            fill(x + w - 3, knobY, 2, knobH, light(wellColour()) ? 0x50000000 : 0x50FFFFFF);
        }
        return (int) scroll[0];
    }

    // Numbers

    /** 1.7k, 3.5M: how the stash manager writes big counts on slots. */
    public static String shortCount(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 10_000) return trim(n / 1000.0) + "k";
        if (n < 1_000_000) return (n / 1000) + "k";
        if (n < 10_000_000) return trim(n / 1_000_000.0) + "M";
        return (n / 1_000_000) + "M";
    }

    private static String trim(double v) {
        String s = String.format(java.util.Locale.ROOT, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    public static String grouped(long n) {
        return String.format(java.util.Locale.ROOT, "%,d", n);
    }

    public static String decimal(double v) {
        if (v >= 100) return grouped(Math.round(v));
        return String.format(java.util.Locale.ROOT, v >= 10 ? "%.1f" : "%.2f", v);
    }
}
