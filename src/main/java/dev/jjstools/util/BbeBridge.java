package dev.jjstools.util;

import dev.jjstools.JJsTools;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;

/**
 * Switches Better Block Entities' sign optimisation off while Sign to Item Frame needs the signs.
 *
 * BBE bakes signs into the world's terrain instead of drawing them one at a time. A baked sign
 * never goes through the sign renderer, which is the only place Sign to Item Frame can hide the
 * board, swap the text or draw the item, so with both on the sign just stays there. BBE keeps its
 * settings in a plain object, so its one sign option is turned off for as long as Sign to Item
 * Frame is on and put back as it was when it goes off.
 *
 * BBE is not a dependency, so everything here is looked up by name and nothing happens without
 * it. Only the value in memory changes: BBE's own config file is left as you set it, so turning
 * Sign to Item Frame off, or launching without it, leaves BBE exactly as before.
 */
public final class BbeBridge {
    private static final String MOD_ID = "betterblockentities";
    private static final String SIGN_OPTION = "optimize.sign";

    /** Where BBE has kept its config object: the inner class in the 1.3 builds, its own class later. */
    private static final String[] SCOPES = {
        "betterblockentities.client.BBE$GlobalScope",
        "betterblockentities.platform.GlobalScope"
    };

    /** What BBE's sign option was before it was turned off here, or null if it was not touched. */
    private static Boolean saved;

    private BbeBridge() {}

    public static boolean installed() {
        return FabricLoader.getInstance().isModLoaded(MOD_ID);
    }

    /** Turns BBE's sign optimisation off. Returns true if it was on and is now off. */
    public static boolean suspendSigns() {
        if (!installed() || saved != null) return false;

        Object option = signOption();
        if (option == null) return false;

        try {
            Object value = option.getClass().getMethod("getValue").invoke(option);
            boolean wasOn = Boolean.TRUE.equals(value);
            if (!wasOn) return false;

            set(option, false);
            saved = true;
            return true;
        } catch (Exception e) {
            JJsTools.LOG.warn("Could not turn off Better Block Entities' sign optimisation", e);
            return false;
        }
    }

    /** Puts BBE's sign optimisation back if it was turned off here. */
    public static void restoreSigns() {
        if (saved == null) return;

        Object option = signOption();
        if (option != null) {
            try {
                set(option, saved);
            } catch (Exception e) {
                JJsTools.LOG.warn("Could not restore Better Block Entities' sign optimisation", e);
            }
        }
        saved = null;
    }

    private static void set(Object option, boolean value) throws Exception {
        Method setter = null;
        for (Method m : option.getClass().getMethods()) {
            if (m.getName().equals("setValue") && m.getParameterCount() == 1
                && (m.getParameterTypes()[0] == Boolean.class || m.getParameterTypes()[0] == Object.class)) {
                setter = m;
                if (m.getParameterTypes()[0] == Boolean.class) break;
            }
        }
        if (setter == null) throw new NoSuchMethodException("setValue");
        setter.invoke(option, value);

        // BBE reads its options through a cache, rebuilt from the options by this.
        Class.forName("betterblockentities.client.gui.config.BBEConfig").getMethod("updateConfigCache").invoke(null);
    }

    private static Object signOption() {
        for (String scope : SCOPES) {
            try {
                Object config = Class.forName(scope).getField("CONFIG").get(null);
                Object main = config.getClass().getField("MAIN").get(config);
                return main.getClass().getMethod("getOption", String.class).invoke(main, SIGN_OPTION);
            } catch (ClassNotFoundException ignored) {
                // Not this version's layout; try the next.
            } catch (Exception e) {
                JJsTools.LOG.warn("Could not read Better Block Entities' config", e);
                return null;
            }
        }
        return null;
    }
}
