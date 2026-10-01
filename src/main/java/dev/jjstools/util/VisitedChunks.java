package dev.jjstools.util;

import dev.jjstools.JJsTools;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

import java.lang.reflect.Method;

/**
 * Asks XaeroPlus whether a chunk is newly generated.
 *
 * A chunk that is NOT new has been loaded by a player at some point, whether that was in 1.12 or
 * last week, which is the closest thing to "someone has been here". XaeroPlus works this out from
 * the block palette, so it does not matter which version generated it.
 *
 * Soft dependency, reached by reflection. Without XaeroPlus installed every chunk is treated as
 * visited, so the filter simply does nothing rather than hiding everything.
 */
public class VisitedChunks {
    private static boolean checked;
    private static Object module;
    private static Method isNewChunk;
    private static boolean warned;

    private VisitedChunks() {}

    public static boolean available() {
        resolve();
        return module != null && isNewChunk != null;
    }

    /** True if a player has loaded this chunk before. Also true when XaeroPlus is missing. */
    public static boolean visited(int chunkX, int chunkZ) {
        resolve();
        if (module == null || isNewChunk == null) return true;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return true;

        try {
            RegistryKey<World> dimension = mc.world.getRegistryKey();
            return !((Boolean) isNewChunk.invoke(module, chunkX, chunkZ, dimension));
        } catch (Throwable t) {
            return true;
        }
    }

    /** One-off warning the first time something asks for the filter without XaeroPlus present. */
    public static boolean warnOnce() {
        if (warned || available()) return false;
        warned = true;
        return true;
    }

    private static synchronized void resolve() {
        if (checked) return;
        checked = true;

        try {
            Class<?> manager = Class.forName("xaeroplus.module.ModuleManager");
            Class<?> palette = Class.forName("xaeroplus.module.impl.PaletteNewChunks");

            module = manager.getMethod("getModule", Class.class).invoke(null, palette);
            isNewChunk = palette.getMethod("isNewChunk", int.class, int.class, RegistryKey.class);
        } catch (Throwable t) {
            JJsTools.LOG.info("XaeroPlus not present, so the visited-chunks filter is inactive");
            module = null;
            isNewChunk = null;
        }
    }
}
