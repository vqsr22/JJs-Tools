package dev.jjstools.tab;

import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.SkinTextures;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Resolving a skin goes through a cache lookup per head per frame. Holding the
 * resolved value for the cache window turns that into one lookup per player per
 * window. Heads look identical.
 */
public final class SkinCache {
    private static final Map<UUID, SkinTextures> CACHE = new HashMap<>();
    private static long stamp;

    private SkinCache() {
    }

    public static SkinTextures get(PlayerListEntry entry, int maxAgeMs, Function<PlayerListEntry, SkinTextures> resolver) {
        if (maxAgeMs <= 0) return resolver.apply(entry);

        long now = System.currentTimeMillis();
        if (now - stamp > maxAgeMs) {
            CACHE.clear();
            stamp = now;
        }

        UUID id = entry.getProfile().id();
        SkinTextures cached = CACHE.get(id);
        if (cached != null) return cached;

        SkinTextures resolved = resolver.apply(entry);
        if (resolved != null) CACHE.put(id, resolved);
        return resolved;
    }

    public static void invalidate() {
        CACHE.clear();
        stamp = 0L;
    }
}
