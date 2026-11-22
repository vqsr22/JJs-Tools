package dev.jjstools.util;

import dev.jjstools.JJsTools;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Adds and removes waypoints in Xaero's Minimap, which shows them on the minimap, in the world
 * and, with Xaero's World Map, on the world map too.
 *
 * Looked up by name rather than compiled against, so JJ's Tools builds and runs without Xaero's
 * Minimap. Goes through the minimap's current session to the waypoint set you have open for the
 * world and dimension you are in, the same way XaeroPlus adds its own. Anything missing, as in a
 * Xaero version that does it differently, makes these do nothing and is reported once.
 */
public final class XaeroWaypoints {
    private XaeroWaypoints() {}

    private static boolean resolved, failed;
    private static Object minimapModule;
    private static Method getCurrentSession, getWorldManager, getCurrentWorld, getCurrentWaypointSet, add, remove;
    private static Constructor<?> newWaypoint;

    public static boolean installed() {
        return FabricLoader.getInstance().isModLoaded("xaerominimap") || FabricLoader.getInstance().isModLoaded("xaerobetterpvp");
    }

    private static boolean resolve() {
        if (resolved) return !failed;
        resolved = true;
        if (!installed()) {
            failed = true;
            return false;
        }
        try {
            Class<?> modules = Class.forName("xaero.hud.minimap.BuiltInHudModules");
            minimapModule = modules.getField("MINIMAP").get(null);
            getCurrentSession = Class.forName("xaero.hud.module.HudModule").getMethod("getCurrentSession");
            getWorldManager = Class.forName("xaero.hud.minimap.module.MinimapSession").getMethod("getWorldManager");
            getCurrentWorld = Class.forName("xaero.hud.minimap.world.MinimapWorldManager").getMethod("getCurrentWorld");
            getCurrentWaypointSet = Class.forName("xaero.hud.minimap.world.MinimapWorld").getMethod("getCurrentWaypointSet");
            Class<?> waypoint = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            Class<?> set = Class.forName("xaero.hud.minimap.waypoint.set.WaypointSet");
            add = set.getMethod("add", waypoint);
            remove = set.getMethod("remove", waypoint);
            newWaypoint = waypoint.getConstructor(int.class, int.class, int.class, String.class, String.class, int.class, int.class, boolean.class);
        } catch (Throwable t) {
            failed = true;
            JJsTools.LOG.warn("Could not reach Xaero's Minimap waypoints; this Xaero version is not supported", t);
        }
        return !failed;
    }

    private static @Nullable Object currentSet() throws Exception {
        Object session = getCurrentSession.invoke(minimapModule);
        if (session == null) return null;
        Object manager = getWorldManager.invoke(session);
        if (manager == null) return null;
        Object world = getCurrentWorld.invoke(manager);
        if (world == null) return null;
        return getCurrentWaypointSet.invoke(world);
    }

    /**
     * A handle for a waypoint that was added: the waypoint and the set it went in, for removing it.
     * Temporary waypoints are not saved and are gone once you leave.
     */
    public record Added(Object set, Object waypoint) {}

    /** Adds a waypoint to the open set; colour is Xaero's colour index (0 to 15). Null if it could not. */
    public static @Nullable Added add(int x, int y, int z, String name, String initials, int colour, boolean temporary) {
        if (!resolve()) return null;
        try {
            Object set = currentSet();
            if (set == null) return null;
            Object waypoint = newWaypoint.newInstance(x, y, z, name, initials, colour, 0, temporary);
            add.invoke(set, waypoint);
            return new Added(set, waypoint);
        } catch (Throwable t) {
            JJsTools.LOG.warn("Could not add a Xaero waypoint", t);
            return null;
        }
    }

    public static void remove(Added added) {
        if (added == null || !resolve()) return;
        try {
            remove.invoke(added.set(), added.waypoint());
        } catch (Throwable ignored) {
        }
    }
}
