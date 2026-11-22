package xaeroplus.feature.render.highlight;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

/** Compile-time stand-in for XaeroPlus; the real interface is used at run time. */
public interface DirectChunkHighlightSupplier {
    Long2LongMap getHighlights(RegistryKey<World> dimension);
}
