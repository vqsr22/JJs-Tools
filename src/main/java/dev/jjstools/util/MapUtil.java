package dev.jjstools.util;

import net.minecraft.util.math.BlockPos;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Xaero waypoint integration is not included. These are no-ops so modules that
 * offer waypoints still compile; their waypoint settings are hidden because
 * JJUtil.XAERO_AVAILABLE is false.
 */
public class MapUtil {
    public static void addWaypoint(BlockPos pos, String name, String initials, Purpose purpose, WpColor color, boolean temp) {
    }

    public static void removeWaypoints(String name, Predicate<BlockPos> posPredicate, Optional<Integer> yOverride) {
    }

    public enum Purpose {
        Normal, Destination
    }

    public enum WpColor {
        Black, Dark_Blue, Dark_Green, Dark_Aqua, Dark_Red, Dark_Purple,
        Gold, Gray, Dark_Gray, Blue, Green, Aqua, Red, Purple, Yellow, White, Random
    }
}
