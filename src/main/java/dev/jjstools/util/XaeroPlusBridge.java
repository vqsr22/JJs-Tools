package dev.jjstools.util;

import dev.jjstools.JJsTools;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * Chunk highlights drawn by XaeroPlus, on Xaero's minimap and world map alike, the same way its
 * own Old Biomes and New Chunks layers are.
 *
 * Compiled against small stand-ins for the XaeroPlus classes (src/stubs), so JJ's Tools builds
 * and runs without it; nothing here is touched unless XaeroPlus is installed. A XaeroPlus too old
 * to have this drawing system makes adding fail, which is reported, and nothing else breaks.
 */
public final class XaeroPlusBridge {
    private XaeroPlusBridge() {}

    public static boolean installed() {
        return FabricLoader.getInstance().isModLoaded("xaeroplus");
    }

    /**
     * Adds a layer of highlighted chunks. The map from each dimension's chunks (ChunkPos.toLong)
     * is read again every refresh; the colour is ARGB. Returns false if XaeroPlus is missing or
     * cannot take it.
     */
    public static boolean addHighlights(String id, Function<RegistryKey<World>, Long2LongMap> highlights, IntSupplier argb, int refreshMs) {
        if (!installed()) return false;
        try {
            xaeroplus.Globals.drawManager.registry().register(
                xaeroplus.feature.render.DrawFeatureFactory.chunkHighlights(id, highlights::apply, argb, refreshMs));
            return true;
        } catch (Throwable t) {
            JJsTools.LOG.warn("XaeroPlus could not take the {} map layer", id, t);
            return false;
        }
    }

    public static void removeHighlights(String id) {
        if (!installed()) return;
        try {
            xaeroplus.Globals.drawManager.registry().unregister(id);
        } catch (Throwable ignored) {
        }
    }
}
