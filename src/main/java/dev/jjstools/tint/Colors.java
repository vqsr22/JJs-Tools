package dev.jjstools.tint;

import net.minecraft.util.DyeColor;

public final class Colors {
    private Colors() {}

    /**
     * Default box colour, same as ShulkerBoxTooltip: the vanilla dye colour with every channel
     * raised to at least 0x26 (15%), so black becomes #262626 instead of near-invisible.
     */
    /** An undyed box has no dye to read a colour from, so this stands in for it. */
    public static final int DEFAULT_UNDYED = 0x976797;

    public static int defaultDyeRgb(DyeColor color) {
        int rgb = rawDyeRgb(color);
        int r = Math.max(0x26, (rgb >> 16) & 0xFF), g = Math.max(0x26, (rgb >> 8) & 0xFF), b = Math.max(0x26, rgb & 0xFF);
        return (r << 16) | (g << 8) | b;
    }

    /** Vanilla dye colours (RGB). */
    public static int rawDyeRgb(DyeColor color) {
        return switch (color) {
            case WHITE -> 0xF9FFFE;
            case ORANGE -> 0xF9801D;
            case MAGENTA -> 0xC74EBD;
            case LIGHT_BLUE -> 0x3AB3DA;
            case YELLOW -> 0xFED83D;
            case LIME -> 0x80C71F;
            case PINK -> 0xF38BAA;
            case GRAY -> 0x474F52;
            case LIGHT_GRAY -> 0x9D9D97;
            case CYAN -> 0x169C9C;
            case PURPLE -> 0x8932B8;
            case BLUE -> 0x3C44AA;
            case BROWN -> 0x835432;
            case GREEN -> 0x5E7C16;
            case RED -> 0xB02E26;
            case BLACK -> 0x1D1D21;
        };
    }

    /** LIGHT_BLUE -> "Light Blue" */
    public static String prettyName(DyeColor color) {
        StringBuilder sb = new StringBuilder();
        for (String part : color.name().split("_")) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(part.charAt(0)).append(part.substring(1).toLowerCase());
        }
        return sb.toString();
    }

    /** amount > 0 blends toward white, amount < 0 toward black (-1 to 1). */
    public static int adjustBrightness(int rgb, double amount) {
        int target = amount >= 0 ? 255 : 0;
        double t = Math.min(1, Math.abs(amount));
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        r = (int) Math.round(r + (target - r) * t);
        g = (int) Math.round(g + (target - g) * t);
        b = (int) Math.round(b + (target - b) * t);
        return (r << 16) | (g << 8) | b;
    }

    /** Multiply mode: blends white toward rgb by strength and returns opaque ARGB. */
    public static int multiplyTint(int rgb, double strength) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        r = (int) Math.round(255 + (r - 255) * strength);
        g = (int) Math.round(255 + (g - 255) * strength);
        b = (int) Math.round(255 + (b - 255) * strength);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Multiplies an RGB colour by factor (clamped). */
    public static int scale(int rgb, double factor) {
        int r = (int) Math.round(Math.min(255, ((rgb >> 16) & 0xFF) * factor));
        int g = (int) Math.round(Math.min(255, ((rgb >> 8) & 0xFF) * factor));
        int b = (int) Math.round(Math.min(255, (rgb & 0xFF) * factor));
        return (r << 16) | (g << 8) | b;
    }

    /**
     * Keeps the box's hue and saturation but flips its lightness away from where it is:
     * dark colours move toward white, light colours toward black. strength 0 = unchanged, 1 = maximum.
     * e.g. black box -> whitish title, white box -> grey title.
     */
    private static final double LIGHT_BOX_EXTRA = 0.12;
    private static final double DARKEN_ABOVE_LUMA = 0.75;
    private static final double LIGHT_TARGET = 0.88;
    private static final double DARK_TARGET = 0.22;

    public static int inverseLightness(int rgb, double strength) {
        double r = ((rgb >> 16) & 0xFF) / 255.0, g = ((rgb >> 8) & 0xFF) / 255.0, b = (rgb & 0xFF) / 255.0;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        double l = (max + min) / 2, h = 0, s = 0;
        if (max != min) {
            double d = max - min;
            s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
            if (max == r) h = (g - b) / d + (g < b ? 6 : 0);
            else if (max == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h /= 6;
        }
        // Direction is decided by perceived brightness (luma), not HSL lightness, so saturated
        // colours like light blue / lime / cyan get a lighter tinted title; only genuinely bright
        // boxes (white, yellow) go darker. Targets are capped so the hue stays visible.
        if (luminance(rgb) < DARKEN_ABOVE_LUMA) {
            l = l + (Math.max(l, LIGHT_TARGET) - l) * strength;
        } else {
            double st = Math.min(1.0, strength + LIGHT_BOX_EXTRA); // light boxes: a bit darker automatically
            l = l - (l - Math.min(l, DARK_TARGET)) * st;
        }
        // Keep the original chroma (colourfulness), not HSL saturation, so near-white/near-black
        // boxes stay grey instead of their tiny tint blowing up into a strong colour.
        double chroma = max - min;
        double room = 1 - Math.abs(2 * l - 1);
        s = room <= 0.0001 ? 0 : Math.min(1.0, chroma / room);
        return hslToRgb(h, s, l);
    }

    private static int hslToRgb(double h, double s, double l) {
        double r, g, b;
        if (s == 0) {
            r = g = b = l;
        } else {
            double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
            double p = 2 * l - q;
            r = hue(p, q, h + 1.0 / 3);
            g = hue(p, q, h);
            b = hue(p, q, h - 1.0 / 3);
        }
        return ((int) Math.round(r * 255) << 16) | ((int) Math.round(g * 255) << 8) | (int) Math.round(b * 255);
    }

    private static double hue(double p, double q, double t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1.0 / 6) return p + (q - p) * 6 * t;
        if (t < 1.0 / 2) return q;
        if (t < 2.0 / 3) return p + (q - p) * (2.0 / 3 - t) * 6;
        return p;
    }

    public static double luminance(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
    }

    /** Vanilla GUI panel grey (0xC6 = 198), the brightness ShulkerBoxTooltip's template panel has. */
    private static final double VANILLA_PANEL = 198.0 / 255.0;

    /**
     * Colorize mode, same maths as ShulkerBoxTooltip: output = box colour x greyscale template.
     * The template is your pack's texture turned greyscale and rescaled so its panel is vanilla grey (198),
     * so the panel ends up at ~78% of the box colour, slots darker, highlights up to the full colour.
     */
    public static int colorize(int argb, int rgb, double refLum, double strength) {
        int a = argb >>> 24;
        int[] src = {(argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF};
        int[] col = {(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF};
        double template = refLum <= 0.001 ? VANILLA_PANEL : luminance(argb) / refLum * VANILLA_PANEL;
        template = Math.max(0.0, Math.min(1.0, template));
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            double c = col[i] * template;
            out[i] = (int) Math.round(Math.max(0, Math.min(255, src[i] + (c - src[i]) * strength)));
        }
        return (a << 24) | (out[0] << 16) | (out[1] << 8) | out[2];
    }
}
