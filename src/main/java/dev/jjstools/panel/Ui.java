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
     * How the panel is coloured. Dark and Light take the main colour from your resource pack (or
     * the colour set in the module); RGB goes round the colour wheel; every other theme is one of
     * monkeytype's, with its background, button shade, main colour, text and label colours.
     */
    public static final class Theme {
        private enum Kind { PackDark, PackLight, Rgb, Palette }

        /** What it is saved as. */
        private final String id;
        public final String title;
        private final Kind kind;
        final int panel, well, button, slot, accent;
        /** The theme's own text colours, or 0 for white or black to suit the background. */
        final int text, textDim;

        private Theme(String id, String title, Kind kind, int panel, int well, int button, int slot, int accent, int text, int textDim) {
            this.id = id;
            this.title = title;
            this.kind = kind;
            this.panel = panel;
            this.well = well;
            this.button = button;
            this.slot = slot;
            this.accent = accent;
            this.text = text;
            this.textDim = textDim;
        }

        public static final Theme Dark = new Theme("Dark", "dark", Kind.PackDark, 0, 0, 0, 0, 0, 0, 0);
        public static final Theme Light = new Theme("Light", "light", Kind.PackLight, 0, 0, 0, 0, 0, 0, 0);
        public static final Theme RGB = new Theme("rgb", "rgb", Kind.Rgb, 0, 0, 0, 0, 0, 0, 0);

        private static final List<Theme> ALL = new ArrayList<>();
        private static final java.util.Map<String, Theme> BY_ID = new java.util.HashMap<>();
        /** What themes were saved as before there were all of monkeytype's. */
        private static final java.util.Map<String, String> OLD = java.util.Map.ofEntries(
            java.util.Map.entry("OliviaDark", "olivia"), java.util.Map.entry("Samurai", "red_samurai"),
            java.util.Map.entry("Catppuccin", "catppuccin"), java.util.Map.entry("Botanical", "botanical"),
            java.util.Map.entry("Stealth", "stealth"), java.util.Map.entry("DarkMagicGirl", "dark_magic_girl"),
            java.util.Map.entry("ModernDolch", "modern_dolch"), java.util.Map.entry("Pastel", "pastel"),
            java.util.Map.entry("Darling", "darling"), java.util.Map.entry("FutureFunk", "future_funk"),
            java.util.Map.entry("Breeze", "breeze"), java.util.Map.entry("Dualshot", "dualshot"),
            java.util.Map.entry("Striker", "striker"), java.util.Map.entry("Discord", "discord"),
            java.util.Map.entry("RGB", "rgb"), java.util.Map.entry("Spotify", "Dark"),
            java.util.Map.entry("White", "Light"), java.util.Map.entry("Olivia", "Light"));

        static {
            add(Dark);
            add(Light);
            // Artisan, after the orange mousepad: orange, its darker stitching, and white.
            add(new Theme("artisan", "artisan", Kind.Palette, 0xFFDC8550, mixColour(0xFFDC8550, 0xFFC36A35, 0.5F),
                0xFFC36A35, 0xFFC36A35, 0xFFFFFFFF, 0xFFFFF6EE, 0xFF8E3F12));
            for (Object[] row : MonkeyThemes.ALL) {
                String id = (String) row[0];
                if (id.equals("rgb")) {
                    add(RGB);
                    continue;
                }
                int bg = 0xFF000000 | (Integer) row[1], main = 0xFF000000 | (Integer) row[2], sub = 0xFF000000 | (Integer) row[3];
                int subAlt = 0xFF000000 | (Integer) row[4], text = 0xFF000000 | (Integer) row[5];
                add(new Theme(id, id.replace('_', ' '), Kind.Palette, bg, mixColour(bg, subAlt, 0.5F), subAlt, subAlt, main, text, sub));
            }
        }

        private static void add(Theme t) {
            ALL.add(t);
            BY_ID.put(t.id, t);
        }

        /** The name it is saved under. */
        public String name() {
            return id;
        }

        /** The colour its name is shown in on the theme list: its main colour, as monkeytype does. */
        public int nameColour() {
            return kind == Kind.Palette ? accent : 0;
        }

        private static int mixColour(int a, int b, float t) {
            int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
            int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
            int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
            return 0xFF000000 | (r << 16) | (g << 8) | bl;
        }

        public static List<Theme> values() {
            return ALL;
        }

        /**
         * Dark and Light first, then the rest from lightest to darkest background, as monkeytype
         * lays its themes out.
         */
        public static List<Theme> ordered() {
            List<Theme> list = new ArrayList<>(ALL);
            list.remove(Dark);
            list.remove(Light);
            list.sort(java.util.Comparator.comparingDouble((Theme t) -> t.kind == Kind.Rgb ? 0.06 : luminance(t.panel)).reversed());
            list.add(0, Light);
            list.add(0, Dark);
            return list;
        }

        boolean fixed() {
            return kind != Kind.PackDark && kind != Kind.PackLight;
        }

        public static Theme of(String name) {
            if (name == null) return Dark;
            Theme t = BY_ID.get(name);
            if (t == null && OLD.containsKey(name)) t = BY_ID.get(OLD.get(name));
            return t == null ? Dark : t;
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
    /** Whether text this frame is drawn in Meteor's custom font. */
    private boolean customFont;
    public Theme theme = Theme.Dark;
    /** Whether the backgrounds are dark, for the few things drawn in their own colours. */
    public boolean dark;
    public int mx = -1, my = -1;
    public long now;

    /** How far each animated button has faded in on hover, from 0 to 1, by key. */
    private final java.util.Map<String, Float> fades = new java.util.HashMap<>();
    private long lastFrame;
    /** Milliseconds since the last frame, for animations. */
    private long frameMs;

    /** This frame's colours for a fixed theme, so RGB can change them as it goes. */
    private int cPanel, cWell, cButton, cSlot, cAccent;

    private final List<Hit> hits = new ArrayList<>();
    private final Deque<int[]> clips = new ArrayDeque<>();
    private List<Text> tooltip;
    public TextBox focus;
    /** Whether the left mouse button is held, for buttons to show they are being pressed. */
    public boolean down;
    public Drag drag;

    /** The pack's own colours, read off its inventory texture: the panel and the inside of a slot. */
    private static int packPanel = 0xFFC6C6C6;
    private static int packSlot = 0xFF8B8B8B;

    public void begin(DrawContext ctx, int mx, int my, float scale, Theme theme) {
        this.ctx = ctx;
        this.tr = mc.textRenderer;
        this.scale = scale;
        this.theme = theme;
        this.mx = mx;
        this.my = my;
        this.now = System.currentTimeMillis();
        this.frameMs = lastFrame == 0 ? 0 : Math.max(0, Math.min(100, now - lastFrame));
        this.lastFrame = now;
        this.customFont = UiFont.ready();
        applyTheme(theme);
        hits.clear();
        clips.clear();
        tooltip = null;
        drawnInLayer = 0;
    }

    /**
     * The colours a theme comes to right now, for things drawn outside the panel such as the
     * Meteor GUI theme: panel, well, button, accent, text and dim text.
     */
    public static int[] palette(Theme theme) {
        Ui u = new Ui();
        u.now = System.currentTimeMillis();
        u.applyTheme(theme);
        return new int[] { u.panelColour(), u.wellColour(), u.buttonColour(), u.accent(), u.fg(), u.fgDim() };
    }

    /** The theme picked in the panel's settings. */
    public static Theme currentTheme() {
        Prefs.Data prefs = Prefs.get();
        return prefs.theme != null ? Theme.of(prefs.theme) : prefs.dark ? Theme.Dark : Theme.Light;
    }

    private void applyTheme(Theme theme) {
        this.theme = theme;
        if (theme == Theme.RGB) {
            // Once round the colour wheel every eight seconds.
            float hue = (now % 8000L) / 8000F;
            cPanel = 0xFF000000 | java.awt.Color.HSBtoRGB(hue, 0.45F, 0.15F);
            cWell = 0xFF000000 | java.awt.Color.HSBtoRGB(hue, 0.45F, 0.11F);
            cButton = 0xFF000000 | java.awt.Color.HSBtoRGB(hue, 0.50F, 0.24F);
            cSlot = 0xFF000000 | java.awt.Color.HSBtoRGB(hue, 0.45F, 0.20F);
            cAccent = 0xFF000000 | java.awt.Color.HSBtoRGB((hue + 0.5F) % 1F, 0.85F, 1.0F);
        } else {
            cPanel = theme.panel;
            cWell = theme.well;
            cButton = theme.button;
            cSlot = theme.slot;
            cAccent = theme.accent;
        }
        this.dark = theme == Theme.Dark || theme.fixed() && !light(cPanel);
    }

    /** Draws something in another theme's colours, as the theme list does for each of its rows. */
    public void withTheme(Theme other, Runnable draw) {
        Theme was = theme;
        int p = cPanel, w = cWell, b = cButton, sl = cSlot, a = cAccent;
        boolean d = dark;
        applyTheme(other);
        try {
            draw.run();
        } finally {
            theme = was;
            cPanel = p;
            cWell = w;
            cButton = b;
            cSlot = sl;
            cAccent = a;
            dark = d;
        }
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
        if (theme.fixed()) return cPanel;
        return theme == Theme.Dark ? shade(base(), 0.22F) : base();
    }

    public int wellColour() {
        if (theme.fixed()) return cWell;
        return theme == Theme.Dark ? shade(slotBase(), 0.16F) : mix(slotBase(), base(), 0.35F);
    }

    private int edge() {
        return light(panelColour()) ? 0x40000000 : 0x30FFFFFF;
    }

    /** The shade buttons sit in, for things drawn like them. */
    public int buttonShade() {
        return buttonColour();
    }

    private int buttonColour() {
        if (theme.fixed()) return cButton;
        return theme == Theme.Dark ? shade(base(), 0.32F) : mix(base(), slotBase(), 0.45F);
    }

    /** Text on the panel itself. */
    public int fg() {
        if (theme.text != 0) return theme.text;
        return light(panelColour()) ? 0xFF303030 : 0xFFEDEDED;
    }

    public int fgDim() {
        if (theme.textDim != 0) return theme.textDim;
        return light(panelColour()) ? 0xFF6A6A6A : 0xFF9A9A9A;
    }

    /** Text inside a list, a box or a well. */
    public int fgWell() {
        if (theme.text != 0) return theme.text;
        return light(wellColour()) ? 0xFF262626 : 0xFFF0F0F0;
    }

    public int fgWellDim() {
        if (theme.textDim != 0) return theme.textDim;
        return light(wellColour()) ? 0xFF5A5A5A : 0xFF969696;
    }

    /** The accent colour from the module's settings, darkened a little on light backgrounds so it reads. */
    public int accent() {
        if (theme.fixed()) return cAccent;
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
        if (s == null || s.isEmpty()) return 0;
        return Math.round(rawWidth(s) * scale);
    }

    /** Text widths at scale 1, remembered, since the same labels are measured every frame. */
    private final java.util.HashMap<String, Float> widths = new java.util.HashMap<>();
    private boolean widthsCustom;
    private int widthsFont;

    private float rawWidth(String s) {
        if (widthsCustom != customFont || widthsFont != UiFont.generation) {
            widths.clear();
            widthsCustom = customFont;
            widthsFont = UiFont.generation;
        }
        Float w = widths.get(s);
        if (w == null) {
            int arrow = arrowAt(s);
            if (arrow >= 0) {
                w = rawWidth(s.substring(0, arrow)) + ARROW_W + rawWidth(s.substring(arrow + 1));
            } else {
                w = customFont && UiFont.handles(s) ? UiFont.width(tr, s) : (float) tr.getWidth(s);
            }
            if (widths.size() > 20000) widths.clear();
            widths.put(s, w);
        }
        return w;
    }

    public void text(String s, int x, int y, int colour) {
        if (s == null || s.isEmpty()) return;
        // The sort arrows are drawn as smooth chevrons rather than the font's blocky triangles.
        int arrow = arrowAt(s);
        if (arrow >= 0) {
            String before = s.substring(0, arrow);
            textRun(before, x, y, colour);
            int ax = x + width(before);
            int aw = Math.max(4, Math.round(ARROW_W * scale));
            int ah = Math.max(3, Math.round(aw * 0.55F));
            int ay = y + (lineH() - ah) / 2 - 1;
            chevron(ax + Math.round(scale), ay, aw - Math.round(2 * scale), ah, s.charAt(arrow) == UP.charAt(0), colour);
            text(s.substring(arrow + 1), ax + aw, y, colour);
            return;
        }
        textRun(s, x, y, colour);
    }

    /** How wide an arrow is, at scale 1, the same as the font's. */
    private static final float ARROW_W = 8;

    private static int arrowAt(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u25B2' || c == '\u25BC') return i;
        }
        return -1;
    }

    private void textRun(String s, int x, int y, int colour) {
        if (s == null || s.isEmpty()) return;
        if (customFont && UiFont.handles(s)) {
            added(UiFont.draw(ctx, tr, s, x, y, scale, colour));
            return;
        }
        added(1);
        if (scale == 1.0F) {
            ctx.drawText(tr, s, x, y, colour, false);
            return;
        }
        dev.jjstools.compat.Gui.push(ctx);
        dev.jjstools.compat.Gui.translate(ctx, x, y);
        dev.jjstools.compat.Gui.scale(ctx, scale, scale);
        ctx.drawText(tr, s, 0, 0, colour, false);
        dev.jjstools.compat.Gui.pop(ctx);
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

    /** Very small text, for things like the times under chat bubbles. */
    public void tiny(String s, int x, int y, int colour) {
        float old = scale;
        scale = old * 0.6F;
        text(s, x, y, colour);
        scale = old;
    }

    public int tinyH() {
        return Math.round(tr.fontHeight * scale * 0.6F);
    }

    public int tinyWidth(String s) {
        float old = scale;
        scale = old * 0.6F;
        int w = width(s);
        scale = old;
        return w;
    }

    public int smallH() {
        return Math.round(tr.fontHeight * scale * 0.75F);
    }

    public int smallWidth(String s) {
        float old = scale;
        scale = old * 0.75F;
        int w = width(s);
        scale = old;
        return w;
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
        // The longest start that fits, found by halving rather than one letter at a time.
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (width(s.substring(0, mid)) + dotsW <= maxW) lo = mid;
            else hi = mid - 1;
        }
        return s.substring(0, lo) + dots;
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
        added(1);
    }

    /*
     * Keeping the game's GUI drawing fast.
     *
     * The game checks every box, item and bit of text drawn against everything already drawn in
     * the same stack of layers, to keep them in order. That is fine for a menu, but a panel full
     * of slots, counts and rounded buttons is thousands of things, and checking each against all
     * the rest every frame is what dragged the frame rate down. Starting a fresh stack every few
     * dozen things keeps each check short; later stacks are drawn over earlier ones, so the order
     * on screen is the same.
     */
    private static final int PER_LAYER = 40;
    private int drawnInLayer;

    /** Counts things drawn, and starts a fresh stack of layers when there have been enough. */
    public void added(int n) {
        drawnInLayer += n;
        if (drawnInLayer >= PER_LAYER) {
            dev.jjstools.compat.Gui.newLayer(ctx);
            drawnInLayer = 0;
        }
    }

    private void outline(int x, int y, int w, int h, int argb) {
        fill(x, y, w, 1, argb);
        fill(x, y + h - 1, w, 1, argb);
        fill(x, y + 1, 1, h - 2, argb);
        fill(x + w - 1, y + 1, 1, h - 2, argb);
    }

    /** A small round light, such as the online light. */
    public void dot(int x, int y, int size, int argb) {
        round(x, y, size, size, size / 2F, argb);
    }

    /** A panel: a soft-cornered card in the panel colour. */
    /** Whether panels are drawn with their outline; set from the settings each frame. */
    public boolean outlines = true;

    public void panel(int x, int y, int w, int h) {
        if (!outlines) {
            round(x, y, w, h, 5, panelColour());
            return;
        }
        // A thin outline, a step towards the text colour, so overlapping panels are told apart.
        round(x, y, w, h, 5, mix(panelColour(), fgDim(), 0.28F));
        round(x + 1, y + 1, w - 2, h - 2, 4, panelColour());
    }

    /** An area for lists and grids, a shade off the panel. */
    public void well(int x, int y, int w, int h) {
        round(x, y, w, h, 4, wellColour());
    }

    /** A thin line across, to separate things. */
    public void divider(int x, int y, int w) {
        fill(x, y, w, 1, alpha(fgDim(), 0.18F));
    }

    private int slotColour() {
        return theme.fixed() ? cSlot : theme == Theme.Dark ? shade(slotBase(), 0.30F) : slotBase();
    }

    /** An inventory slot: a soft square in the slot colour. */
    public void slot(int x, int y) {
        round(x + 1, y + 1, 16, 16, 2, slotColour());
    }

    /**
     * A run of slots, cols wide, the first at x, y: one block of the slot colour with a thin gap
     * between slots, drawn as a few long strips rather than a box for every slot.
     */
    public void slotGrid(int x, int y, int cols, int count) {
        if (count <= 0) return;
        int base = slotColour();
        int gap = wellColour();
        int fullRows = count / cols;
        int rest = count % cols;
        if (fullRows > 0) fill(x, y, cols * 18, fullRows * 18, base);
        if (rest > 0) fill(x, y + fullRows * 18, rest * 18, 18, base);
        int rows = fullRows + (rest > 0 ? 1 : 0);
        for (int r = 0; r < rows; r++) fill(x, y + r * 18, r < fullRows ? cols * 18 : rest * 18, 1, gap);
        for (int c = 0; c < cols; c++) {
            int tall = fullRows * 18 + (c < rest ? 18 : 0);
            fill(x + c * 18, y, 1, tall, gap);
        }
    }

    // Smooth shapes, drawn in screen pixels so their edges are soft at any scale.

    private interface Pixels {
        void draw(int x, int y, int w, int h);
    }

    /** Works out where a rectangle falls in screen pixels, and draws in those for a moment. */
    private void inPixels(int x, int y, int w, int h, Pixels draw) {
        float sf = dev.jjstools.compat.Gui.scaleFactor();
        float sx = dev.jjstools.compat.Gui.scaleX(ctx), sy = dev.jjstools.compat.Gui.scaleY(ctx), ox = dev.jjstools.compat.Gui.offsetX(ctx), oy = dev.jjstools.compat.Gui.offsetY(ctx);
        int x1 = Math.round((sx * x + ox) * sf);
        int y1 = Math.round((sy * y + oy) * sf);
        int x2 = Math.round((sx * (x + w) + ox) * sf);
        int y2 = Math.round((sy * (y + h) + oy) * sf);
        if (x2 <= x1 || y2 <= y1) return;
        dev.jjstools.compat.Gui.push(ctx);
        dev.jjstools.compat.Gui.identity(ctx);
        dev.jjstools.compat.Gui.scale(ctx, 1F / sf, 1F / sf);
        try {
            draw.draw(x1, y1, x2 - x1, y2 - y1);
        } finally {
            dev.jjstools.compat.Gui.pop(ctx);
        }
    }

    /** How many screen pixels one unit is here. */
    private float pixelsPerUnit() {
        return Math.abs(dev.jjstools.compat.Gui.scaleX(ctx)) * dev.jjstools.compat.Gui.scaleFactor();
    }

    /** A rectangle with round corners of radius r. */
    public void round(int x, int y, int w, int h, float r, int argb) {
        if (w <= 0 || h <= 0 || (argb >>> 24) == 0) return;
        int pr = Math.round(r * pixelsPerUnit());
        if (pr < 1) {
            fill(x, y, w, h, argb);
            return;
        }
        inPixels(x, y, w, h, (px, py, pw, ph) -> roundPixels(px, py, pw, ph, pr, argb));
    }

    /**
     * A rounded box with a ring round it, the ring thick units wide on every side.
     *
     * Worked out in screen pixels from one rounded outline, the inside pulled in by the same
     * whole number of pixels on all four sides. Drawn as two separate boxes, each was rounded to
     * pixels on its own, so the ring came out a pixel thicker on some sides than others.
     */
    public void ring(int x, int y, int w, int h, float r, float thick, int ringArgb, int fillArgb) {
        if (w <= 0 || h <= 0) return;
        float ppu = pixelsPerUnit();
        int pr = Math.round(r * ppu);
        int t = Math.max(1, Math.round(thick * ppu));
        inPixels(x, y, w, h, (px, py, pw, ph) -> {
            roundPixels(px, py, pw, ph, pr, ringArgb);
            if (pw > t * 2 && ph > t * 2) roundPixels(px + t, py + t, pw - t * 2, ph - t * 2, Math.max(0, pr - t), fillArgb);
        });
    }

    private void roundPixels(int px, int py, int pw, int ph, int pr, int argb) {
        if ((argb >>> 24) == 0) return;
        int rr = Math.min(pr, Math.min(pw, ph) / 2);
        if (rr < 1) {
            ctx.fill(px, py, px + pw, py + ph, argb);
            return;
        }
        added(7);
        ctx.fill(px + rr, py, px + pw - rr, py + ph, argb);
        ctx.fill(px, py + rr, px + rr, py + ph - rr, argb);
        ctx.fill(px + pw - rr, py + rr, px + pw, py + ph - rr, argb);
        UiShapes.Shape c = UiShapes.circle(rr);
        ctx.drawTexture(dev.jjstools.compat.Gui.TEXTURED, c.id(), px, py, 0, 0, rr, rr, rr, rr, c.w(), c.h(), argb);
        ctx.drawTexture(dev.jjstools.compat.Gui.TEXTURED, c.id(), px + pw - rr, py, rr, 0, rr, rr, rr, rr, c.w(), c.h(), argb);
        ctx.drawTexture(dev.jjstools.compat.Gui.TEXTURED, c.id(), px, py + ph - rr, 0, rr, rr, rr, rr, rr, c.w(), c.h(), argb);
        ctx.drawTexture(dev.jjstools.compat.Gui.TEXTURED, c.id(), px + pw - rr, py + ph - rr, rr, rr, rr, rr, rr, rr, c.w(), c.h(), argb);
    }

    private void shape(java.util.function.BiFunction<Integer, Integer, UiShapes.Shape> make, int x, int y, int w, int h, int argb) {
        if ((argb >>> 24) == 0) return;
        inPixels(x, y, w, h, (px, py, pw, ph) -> {
            added(1);
            UiShapes.Shape s = make.apply(pw, ph);
            ctx.drawTexture(dev.jjstools.compat.Gui.TEXTURED, s.id(), px, py, 0, 0, pw, ph, pw, ph, s.w(), s.h(), argb);
        });
    }

    /** A thin, smooth chevron, pointing down or up. */
    public void chevron(int x, int y, int w, int h, boolean up, int argb) {
        shape((pw, ph) -> UiShapes.chevron(pw, ph, up), x, y, w, h, argb);
    }

    /** A push pin. */
    public void pin(int x, int y, int size, int argb) {
        shape((pw, ph) -> UiShapes.pin(Math.min(pw, ph)), x, y, size, size, argb);
    }

    /** A plus or minus, for steppers. */
    public void plusMinus(int cx, int cy, int size, boolean plus, int argb) {
        int t = Math.max(1, Math.round(size / 6F));
        fill(cx - size / 2, cy - t / 2, size, t, argb);
        if (plus) fill(cx - t / 2, cy - size / 2, t, size, argb);
    }

    public void item(ItemStack stack, int x, int y) {
        ctx.drawItem(stack, x, y);
        added(1);
    }

    /** An item drawn size units across instead of 16, as the dock's icons are. */
    public void item(ItemStack stack, int x, int y, int size) {
        if (size == 16) {
            item(stack, x, y);
            return;
        }
        dev.jjstools.compat.Gui.push(ctx);
        dev.jjstools.compat.Gui.translate(ctx, x, y);
        dev.jjstools.compat.Gui.scale(ctx, size / 16F, size / 16F);
        ctx.drawItem(stack, 0, 0);
        dev.jjstools.compat.Gui.pop(ctx);
        added(1);
    }

    /**
     * An item's number in the corner of its slot, drawn small enough to stay inside the slot
     * however big it is, so a grid of big counts does not run together.
     */
    public void itemCount(ItemStack stack, int x, int y, String count) {
        float s = count.length() <= 2 ? 1.0F : count.length() == 3 ? 0.75F : 0.6F;
        boolean custom = customFont && UiFont.handles(count);
        float raw = custom ? UiFont.width(tr, count) : tr.getWidth(count);
        int w = Math.round(raw * s);
        dev.jjstools.compat.Gui.push(ctx);
        dev.jjstools.compat.Gui.translate(ctx, x + 17 - w, y + 17 - Math.round(8 * s));
        dev.jjstools.compat.Gui.scale(ctx, s, s);
        if (custom) {
            added(UiFont.draw(ctx, tr, count, 0, 0, 1F, 0xFFFFFFFF));
        } else {
            ctx.drawTextWithShadow(tr, count, 0, 0, 0xFFFFFFFF);
            added(1);
        }
        dev.jjstools.compat.Gui.pop(ctx);
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
            added(2);
            return;
        }
        int hash = name == null ? 0 : name.toLowerCase(java.util.Locale.ROOT).hashCode();
        int colour = 0xFF000000 | (0x404040 + ((hash & 0x7F) << 16) + (((hash >> 7) & 0x7F) << 8) + ((hash >> 14) & 0x7F));
        fill(x, y, size, size, colour);
        String letter = name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        text(letter, x + (size - width(letter)) / 2, y + (size - lineH()) / 2 + 1, 0xFFFFFFFF);
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

    /**
     * The colours of something pressable right now, as monkeytype does them: at rest it sits in
     * the button shade with the text colour, or the main colour when it is on; under the mouse
     * it turns straight to the text colour with the background colour on it.
     * Gives the background and the colour for whatever is written on it.
     */
    private int[] look(int x, int y, int w, int h, int rest, boolean on, boolean enabled) {
        boolean hover = enabled && hovered(x, y, w, h);
        // Held down, monkeytype's buttons go to the dim colour, so a click can be seen.
        if (hover && down) return new int[] { fgDim(), panelColour(), 1 };
        if (hover) return new int[] { fg(), panelColour(), 1 };
        int bg = on ? accent() : rest;
        int ink = on ? panelColour() : enabled ? fg() : alpha(fgDim(), 0.7F);
        return new int[] { bg, ink, 0 };
    }

    /**
     * The shape behind something pressable: a touch smaller at once while held, easing back
     * when let go, and a short pulse when it turns on. Only the shape moves; what is written on
     * it stays put, so it reads steadily.
     */
    private void shape(int x, int y, int w, int h, int colour, boolean on, boolean enabled) {
        // Known by where it is, since these are drawn afresh every frame.
        long key = ((long) x << 48) ^ ((long) y << 32) ^ ((long) w << 16) ^ h;
        double k = dev.jjstools.util.Motion.press(key, enabled && down && hovered(x, y, w, h))
            * dev.jjstools.util.Motion.pulseOnTurn(key, on);
        if (k == 1) {
            round(x, y, w, h, 3, colour);
            return;
        }
        dev.jjstools.compat.Gui.push(ctx);
        dev.jjstools.compat.Gui.translate(ctx, x + w / 2F, y + h / 2F);
        dev.jjstools.compat.Gui.scale(ctx, (float) k, (float) k);
        dev.jjstools.compat.Gui.translate(ctx, -(x + w / 2F), -(y + h / 2F));
        round(x, y, w, h, 3, colour);
        dev.jjstools.compat.Gui.pop(ctx);
    }

    /** Blends two colours, transparency included. */
    private static int mixArgb(int a, int b, float t) {
        int aa = (a >>> 24) & 0xFF, ba = (b >>> 24) & 0xFF;
        int alpha = Math.round(aa * (1 - t) + ba * t);
        return (alpha << 24) | (mix(a, b, t) & 0xFFFFFF);
    }

    /** A button. Returns true while hovered. */
    public boolean button(int x, int y, int w, int h, String label, boolean enabled, Runnable action) {
        int[] c = look(x, y, w, h, buttonColour(), false, enabled);
        shape(x, y, w, h, c[0], false, enabled);
        String shown = label.length() <= 1 ? label : fit(label, w - 6);
        textCentred(shown, x + w / 2, y + (h - lineH()) / 2 + 1, c[1]);
        if (enabled && action != null) onClick(x, y, w, h, action);
        return c[2] == 1;
    }

    public boolean button(int x, int y, int w, int h, String label, Runnable action) {
        return button(x, y, w, h, label, true, action);
    }

    /** One of a set of choices, like monkeytype's: in the button shade, or the main colour when chosen. */
    public boolean choice(int x, int y, int w, int h, String label, boolean on, Runnable action) {
        int[] c = look(x, y, w, h, buttonColour(), on, true);
        shape(x, y, w, h, c[0], on, true);
        textCentred(fit(label, w - 6), x + w / 2, y + (h - lineH()) / 2 + 1, c[1]);
        if (action != null) onClick(x, y, w, h, action);
        return c[2] == 1;
    }

    /**
     * A choice with its label in a given font, for the font page's previews; null draws it in
     * Minecraft's font.
     */
    public boolean choiceIn(UiFont.Face face, int x, int y, int w, int h, String label, boolean on, Runnable action) {
        int[] c = look(x, y, w, h, buttonColour(), on, true);
        shape(x, y, w, h, c[0], on, true);
        float raw = face == null ? tr.getWidth(label) : face.width(tr, label);
        int tw = Math.round(raw * scale);
        int tx = x + (w - tw) / 2, ty = y + (h - lineH()) / 2 + 1;
        if (face != null) {
            added(face.draw(ctx, tr, label, tx, ty, scale, c[1]));
        } else {
            added(1);
            dev.jjstools.compat.Gui.push(ctx);
            dev.jjstools.compat.Gui.translate(ctx, tx, ty);
            dev.jjstools.compat.Gui.scale(ctx, scale, scale);
            ctx.drawText(tr, label, 0, 0, c[1], false);
            dev.jjstools.compat.Gui.pop(ctx);
        }
        if (action != null) onClick(x, y, w, h, action);
        return c[2] == 1;
    }

    /** A square button with a plus or minus on it, drawn rather than written so it fits any size. */
    public boolean stepButton(int x, int y, int size, boolean plus, Runnable action) {
        int[] c = look(x, y, size, size, buttonColour(), false, true);
        shape(x, y, size, size, c[0], false, true);
        plusMinus(x + size / 2, y + size / 2, Math.max(3, size / 2 - 1), plus, c[1]);
        if (action != null) onClick(x, y, size, size, action);
        return c[2] == 1;
    }

    /** A tab with an item and a label, as the dock's panel buttons are, the item sized to fit. */
    public boolean tab(int x, int y, int w, int h, ItemStack icon, int iconSize, String label, boolean on, Runnable action) {
        int[] c = look(x, y, w, h, buttonColour(), on, true);
        shape(x, y, w, h, c[0], on, true);
        // The item and label together, centred in the tab; no item when icons are hidden.
        int inner = iconSize > 0 ? iconSize + 4 + width(label) : width(label);
        int ix = x + (w - inner) / 2;
        if (iconSize > 0) item(icon, ix, y + (h - iconSize) / 2, iconSize);
        text(label, iconSize > 0 ? ix + iconSize + 4 : ix, y + (h - lineH()) / 2 + 1, c[1]);
        if (action != null) onClick(x, y, w, h, action);
        return c[2] == 1;
    }

    /** How wide a tab is for its label and item size. */
    public int tabWidth(String label, int iconSize) {
        return iconSize > 0 ? 4 + iconSize + 4 + width(label) + 7 : width(label) + 20;
    }

    /** Moves a hover fade towards on or off, over the Button Fade Time setting, and gives how far in it is. */
    private float fade(String key, boolean on) {
        float t = fades.getOrDefault(key, 0F);
        int ms = dev.jjstools.modules.ControlPanel.fadeTime();
        float step = ms <= 0 ? 1F : frameMs / (float) ms;
        t = on ? Math.min(1F, t + step) : Math.max(0F, t - step);
        if (fades.size() > 4000) fades.clear();
        fades.put(key, t);
        // Eased, so it starts and ends gently.
        return t * t * (3 - 2 * t);
    }

    public static int alpha(int argb, float a) {
        return (Math.round(((argb >>> 24) & 0xFF) * a) << 24) | (argb & 0xFFFFFF);
    }

    /** The red close button; an x fades in on it while the mouse is over it, as macOS does. */
    public boolean closeButton(String key, int x, int y, int size, Runnable action) {
        boolean hover = hovered(x, y, size, size);
        float t = fade("close:" + key, hover);
        round(x, y, size, size, size * 0.3F, mix(0xFFE0443C, 0xFFFF6B63, t));
        if (t > 0) {
            int in = Math.max(2, Math.round(size * 0.27F));
            shape((pw, ph) -> UiShapes.cross(Math.min(pw, ph)), x + in, y + in, size - in * 2, size - in * 2, alpha(0xFF6B0E0A, t));
        }
        if (action != null) onClick(x, y, size, size, action);
        return hover;
    }

    /**
     * The green button that fills the screen with a window and puts it back. Two triangles fade
     * in on it while the mouse is over it, pointing out to make it bigger, or in to put it back.
     */
    public boolean maximiseButton(String key, int x, int y, int size, boolean maximised, Runnable action) {
        boolean hover = hovered(x, y, size, size);
        float t = fade("max:" + key, hover);
        round(x, y, size, size, size * 0.3F, mix(0xFF2FB344, 0xFF4CD263, t));
        if (t > 0) {
            int in = Math.max(2, Math.round(size * 0.25F));
            shape((pw, ph) -> UiShapes.triangles(Math.min(pw, ph), maximised), x + in, y + in, size - in * 2, size - in * 2, alpha(0xFF0B4A16, t));
        }
        if (action != null) onClick(x, y, size, size, action);
        return hover;
    }

    /**
     * A button that can be on or off: in the button shade when off, like every other button, so
     * it stands out from the panel, and in the main colour when on.
     */
    public boolean toggle(int x, int y, int w, int h, String label, boolean on, Runnable action) {
        int[] c = look(x, y, w, h, buttonColour(), on, true);
        shape(x, y, w, h, c[0], on, true);
        textCentred(fit(label, w - 6), x + w / 2, y + (h - lineH()) / 2 + 1, c[1]);
        if (action != null) onClick(x, y, w, h, action);
        return c[2] == 1;
    }

    /** A square button with an item for its picture, as the creative menu's tabs have. */
    public boolean iconButton(int x, int y, int size, ItemStack icon, boolean on, String tip, Runnable action) {
        int[] c = look(x, y, size, size, buttonColour(), on, true);
        shape(x, y, size, size, c[0], on, true);
        item(icon, x + (size - 16) / 2, y + (size - 16) / 2);
        if (c[2] == 1 && tip != null) tooltip(tip);
        if (action != null) onClick(x, y, size, size, action);
        return c[2] == 1;
    }

    /** A text box. Clicking focuses it, right-clicking empties it; typing goes to whichever has focus. */
    public void textBox(TextBox box, int x, int y, int w, int h) {
        boolean focused = focus == box;
        round(x, y, w, h, 3, buttonColour());

        int pad = 5;
        int ty = y + (h - lineH()) / 2 + 1;
        clip(x + 1, y, w - 2, h);
        if (box.text.isEmpty() && !focused) {
            int px = box.centred ? x + (w - width(box.placeholder)) / 2 : x + pad;
            text(box.placeholder, px, ty, fgDim());
        } else {
            // Shown from far enough along that the cursor is always in view.
            String shown = box.text;
            int room = w - pad * 2 - 4;
            int start = 0;
            while (start < box.cursor && width(shown.substring(start, box.cursor)) > room) start++;
            String visible = shown.substring(start);
            // Centred boxes keep their text in the middle while it fits.
            int left = box.centred && width(visible) <= room ? x + (w - width(visible)) / 2 : x + pad;
            text(visible, left, ty, fg());
            if (focused) {
                // A caret that fades in and out rather than blinking hard.
                float wave = (float) (0.5 + 0.5 * Math.cos((now % 1000L) / 1000.0 * Math.PI * 2));
                int cx = left + width(shown.substring(start, Math.max(start, box.cursor)));
                round(cx, ty - 1, 1, lineH() + 1, 0.5F, alpha(accent(), 0.25F + 0.75F * wave));
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
            round(x + w - 4, knobY + 1, 3, knobH - 2, 1.5F, alpha(fgDim(), 0.45F));
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
