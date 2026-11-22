package xaeroplus.feature.render;

/** Compile-time stand-in for XaeroPlus; the real interface is used at run time. */
public interface DrawFeature {
    String id();
    void invalidateCache();
}
