package xaeroplus.feature.render;

import xaeroplus.feature.render.highlight.DirectChunkHighlightSupplier;

import java.util.function.IntSupplier;

/** Compile-time stand-in for XaeroPlus; the real interface is used at run time. */
public interface DrawFeatureFactory {
    static DrawFeature chunkHighlights(String id, DirectChunkHighlightSupplier highlights, IntSupplier color, int refreshIntervalMs) {
        throw new UnsupportedOperationException();
    }
}
