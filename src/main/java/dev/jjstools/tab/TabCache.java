package dev.jjstools.tab;

import net.minecraft.client.network.PlayerListEntry;

import java.util.List;

/**
 * collectPlayerEntries() sorts the whole player list and runs more than once per
 * frame (Meteor's Better Tab calls it a second time for column height). This
 * holds the finished, trimmed list for a short window.
 */
public final class TabCache {
    private static List<PlayerListEntry> cached;
    private static long stamp;

    private TabCache() {
    }

    /** The cached list if it is younger than maxAgeMs, otherwise null. */
    public static List<PlayerListEntry> get(int maxAgeMs) {
        if (maxAgeMs <= 0 || cached == null) return null;
        if (System.currentTimeMillis() - stamp > maxAgeMs) return null;
        return cached;
    }

    public static void put(List<PlayerListEntry> list) {
        cached = list;
        stamp = System.currentTimeMillis();
    }

    public static void invalidate() {
        cached = null;
        stamp = 0L;
    }
}
