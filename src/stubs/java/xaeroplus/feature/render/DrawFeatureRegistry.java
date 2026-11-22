package xaeroplus.feature.render;

/** Compile-time stand-in for XaeroPlus; the real class is used at run time. */
public class DrawFeatureRegistry {
    public synchronized void register(DrawFeature feature) {}
    public synchronized void unregister(String id) {}
}
