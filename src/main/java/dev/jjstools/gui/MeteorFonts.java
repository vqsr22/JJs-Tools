package dev.jjstools.gui;

import dev.jjstools.JJsTools;
import dev.jjstools.panel.PanelFonts;
import dev.jjstools.panel.Prefs;
import meteordevelopment.meteorclient.renderer.Fonts;
import meteordevelopment.meteorclient.renderer.text.FontFace;
import meteordevelopment.meteorclient.renderer.text.FontFamily;
import meteordevelopment.meteorclient.renderer.text.FontInfo;
import meteordevelopment.meteorclient.renderer.text.SystemFontFace;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.render.FontUtils;

import java.nio.file.Path;

/**
 * Puts a Control Panel font into Meteor's GUI too, from the fonts tab.
 *
 * Meteor reads fonts from files, so the panel's font is handed over as one: the download, or
 * the bundled Roboto Mono written out beside the downloads. It is added to Meteor's own font
 * list if it is not there already, and set as Meteor's custom font. A font still downloading is
 * remembered and set the moment it arrives. "minecraft" turns Meteor's custom font off, and
 * "meteor" leaves Meteor's font as it is.
 */
public final class MeteorFonts {
    private static String pending;
    private static String status = "";

    private MeteorFonts() {}

    /** Picks a font for both the panel and Meteor's GUI. */
    public static void pick(String id) {
        Prefs.get().font = id;
        Prefs.save();
        PanelFonts.retry(id);
        pending = id;
        status = "";
        tick();
    }

    /** Gives Meteor's GUI a font without changing the Control Panel's, for JJ's theme keeping the two the same. */
    public static void applyToMeteor(String id) {
        pending = id;
        tick();
    }

    // Where the font is used

    /** A shadow under the GUI's text, under JJ's theme. */
    public static void setShadow(boolean on) {
        if (meteordevelopment.meteorclient.gui.GuiThemes.get() instanceof dev.jjstools.gui.jjtheme.JJGuiTheme jj) jj.textShadow.set(on);
    }

    @SuppressWarnings("unchecked")
    private static meteordevelopment.meteorclient.settings.Setting<Boolean> hudSetting() {
        var hud = meteordevelopment.meteorclient.systems.hud.Hud.get();
        return hud == null ? null : (meteordevelopment.meteorclient.settings.Setting<Boolean>) hud.settings.get("custom-font");
    }

    /** Whether Meteor's HUD (coordinates and the rest) draws in the picked font rather than Minecraft's. */
    public static boolean hudFont() {
        var s = hudSetting();
        return s != null && s.get();
    }

    public static void setHudFont(boolean on) {
        var s = hudSetting();
        if (s != null) s.set(on);
    }

    private static dev.jjstools.modules.TabOptimizer tab() {
        var modules = meteordevelopment.meteorclient.systems.modules.Modules.get();
        return modules == null ? null : modules.get(dev.jjstools.modules.TabOptimizer.class);
    }

    /** Whether Tab Optimizer's tab list draws in the picked font rather than Minecraft's. */
    public static boolean tabFont() {
        var t = tab();
        return t != null && t.meteorText.get();
    }

    public static void setTabFont(boolean on) {
        var t = tab();
        if (t != null) t.meteorText.set(on);
    }

    /** A few words on how the last pick went, for the fonts tab. */
    public static String status() {
        return status;
    }

    /** Called every tick: sets a font that was still downloading once it has arrived. */
    public static void tick() {
        String id = pending;
        if (id == null) return;
        Config config = Config.get();
        if (config == null) return;

        if (id.equals(PanelFonts.MINECRAFT)) {
            config.customFont.set(false);
            pending = null;
            return;
        }
        if (id.equals(PanelFonts.METEOR)) {
            pending = null;
            return;
        }

        if ("failed".equals(PanelFonts.state(id))) {
            status = id + " could not be downloaded";
            pending = null;
            return;
        }
        Path file = PanelFonts.fileFor(id);
        if (file == null) {
            status = "downloading " + id + "...";
            return;
        }
        pending = null;
        try {
            FontFace face = faceFor(file);
            if (face == null) {
                status = "meteor could not read " + id;
                return;
            }
            config.customFont.set(true);
            config.font.set(face);
            status = "";
        } catch (Exception e) {
            status = "meteor could not load " + id;
            JJsTools.LOG.warn("Could not give Meteor the font " + id, e);
        }
    }

    /** The font file as one of Meteor's font faces, added to Meteor's font list if it is new. */
    private static FontFace faceFor(Path file) {
        FontInfo info = FontUtils.getSysFontInfo(file.toFile());
        if (info == null) return null;
        FontFamily family = Fonts.getFamily(info.family());
        if (family != null && family.hasType(info.type())) return family.get(info.type());
        if (family == null) {
            family = new FontFamily(info.family());
            Fonts.FONT_FAMILIES.add(family);
            Fonts.FONT_FAMILIES.sort(java.util.Comparator.comparing(FontFamily::getName));
        }
        FontFace face = new SystemFontFace(info, file);
        family.addFont(face);
        return face;
    }
}
