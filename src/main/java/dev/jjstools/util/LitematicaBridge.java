package dev.jjstools.util;

import dev.jjstools.JJsTools;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The few things the printer needs from Litematica, reached without depending on it.
 *
 * Litematica keeps every loaded schematic placement in one world of its own, built from the
 * same blocks as the real one, so asking that world for the block at a position is asking what
 * the schematics want there, wherever they are placed. The render layer range is the part of
 * them you have chosen to show.
 *
 * Looked up by name rather than compiled against, so JJ's Tools builds and runs without
 * Litematica installed and the printer simply reports that it is missing. The schematic world is
 * a Minecraft World underneath, so once found it is used as one.
 */
public final class LitematicaBridge {
    private static boolean resolved;
    private static Method schematicWorld;
    private static Method layerRange;
    private static Method withinRange;
    private static Method placementManager;
    private static Method allPlacements;
    private static Method placementEnabled;
    private static Method subRegionBoxes;
    private static Object renderingEnabled;
    private static Method boxPos1;
    private static Method boxPos2;

    private LitematicaBridge() {}

    public static boolean installed() {
        return FabricLoader.getInstance().isModLoaded("litematica");
    }

    /** Litematica's world of placed schematics, or null if there is none or Litematica is missing. */
    public static @Nullable World schematicWorld() {
        if (!resolve() || schematicWorld == null) return null;
        try {
            return (World) schematicWorld.invoke(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Whether a position is inside the layers Litematica is set to show. True if that cannot be told. */
    public static boolean inLayerRange(BlockPos pos) {
        if (!resolve() || layerRange == null || withinRange == null) return true;
        try {
            Object range = layerRange.invoke(null);
            return range == null || (boolean) withinRange.invoke(range, pos);
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * The boxes your schematics take up, as min x, y, z and max x, y, z, inclusive: every shown
     * sub-region of every enabled placement. Inside these, air in the schematic means the spot
     * should be empty. Empty if that cannot be told.
     */
    public static List<int[]> placementBoxes() {
        List<int[]> boxes = new ArrayList<>();
        if (!resolve() || allPlacements == null) return boxes;
        try {
            Object manager = placementManager.invoke(null);
            if (manager == null) return boxes;

            for (Object placement : (Collection<?>) allPlacements.invoke(manager)) {
                if (!(boolean) placementEnabled.invoke(placement)) continue;

                Object map = subRegionBoxes.invoke(placement, renderingEnabled);
                for (Object box : ((java.util.Map<?, ?>) map).values()) {
                    BlockPos a = (BlockPos) boxPos1.invoke(box);
                    BlockPos b = (BlockPos) boxPos2.invoke(box);
                    if (a == null || b == null) continue;
                    boxes.add(new int[] {
                        Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                        Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())
                    });
                }
            }
        } catch (Exception e) {
            // Leaves what was found so far.
        }
        return boxes;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void resolvePlacements() {
        try {
            placementManager = Class.forName("fi.dy.masa.litematica.data.DataManager").getMethod("getSchematicPlacementManager");
            allPlacements = placementManager.getReturnType().getMethod("getAllSchematicsPlacements");

            Class<?> placement = Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacement");
            Class<?> required = Class.forName("fi.dy.masa.litematica.schematic.placement.SubRegionPlacement$RequiredEnabled");
            placementEnabled = placement.getMethod("isEnabled");
            subRegionBoxes = placement.getMethod("getSubRegionBoxes", required);
            renderingEnabled = Enum.valueOf((Class) required, "RENDERING_ENABLED");

            Class<?> box = Class.forName("fi.dy.masa.litematica.selection.Box");
            boxPos1 = box.getMethod("getPos1");
            boxPos2 = box.getMethod("getPos2");
        } catch (Exception e) {
            JJsTools.LOG.warn("Could not find Litematica's schematic placements, so blocks where a schematic wants air are not checked", e);
            allPlacements = null;
        }
    }

    private static synchronized boolean resolve() {
        if (resolved) return schematicWorld != null;
        resolved = true;
        if (!installed()) return false;

        try {
            schematicWorld = Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler").getMethod("getSchematicWorld");
        } catch (Exception e) {
            JJsTools.LOG.warn("Could not find Litematica's schematic world", e);
            return false;
        }

        try {
            layerRange = Class.forName("fi.dy.masa.litematica.data.DataManager").getMethod("getRenderLayerRange");
            withinRange = layerRange.getReturnType().getMethod("isPositionWithinRange", BlockPos.class);
        } catch (Exception e) {
            // Without it every layer counts, which is still a working printer.
            layerRange = null;
            withinRange = null;
        }

        resolvePlacements();
        return true;
    }
}
