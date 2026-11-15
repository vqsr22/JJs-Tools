package dev.jjstools.util;

import dev.jjstools.modules.Incognito;
import meteordevelopment.meteorclient.systems.modules.Modules;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * The Xaero settings Incognito turns off while it is on: World Map's cursor coordinates and its
 * waypoints, and the Minimap's waypoints. Matched by the option objects themselves, looked up once
 * by reflection, since the two mods could share an option name.
 */
public final class XaeroIncognito {
    private XaeroIncognito() {}

    private static Set<Object> options;

    public static boolean hides(Object option) {
        Modules modules = Modules.get();
        if (modules == null) return false;
        Incognito incognito = modules.get(Incognito.class);
        if (incognito == null || !incognito.isActive() || !incognito.hidesXaero()) return false;
        if (options == null) options = resolve();
        return options.contains(option);
    }

    private static Set<Object> resolve() {
        Set<Object> found = Collections.newSetFromMap(new IdentityHashMap<>());
        add(found, "xaero.map.common.config.option.WorldMapProfiledConfigOptions", "COORDINATES");
        add(found, "xaero.map.common.config.option.WorldMapProfiledConfigOptions", "WAYPOINTS");
        add(found, "xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions", "WAYPOINTS_ON_MINIMAP");
        return found;
    }

    private static void add(Set<Object> into, String owner, String field) {
        try {
            Object value = Class.forName(owner).getField(field).get(null);
            if (value != null) into.add(value);
        } catch (Throwable ignored) {
            // That mod is not installed, or has renamed the option.
        }
    }
}
