package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import dev.jjstools.util.XaeroPlusBridge;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongMaps;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashMap;
import java.util.Map;

/**
 * Shows lush caves on Xaero's minimap and world map, through XaeroPlus.
 *
 * The server sends every chunk's biomes along with its blocks, a few per section, so a chunk with
 * lush caves anywhere under it says so the moment it loads, however deep the cave is and whether
 * or not you have seen it. Each such chunk is highlighted the way XaeroPlus's own Old Biomes and
 * New Chunks layers are, and stays highlighted after it unloads until you leave the server.
 */
public class LushCaveMap extends Module {
    private static final String LAYER = "JJsToolsLushCaves";

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<SettingColor> colour = sgGeneral.add(new ColorSetting.Builder()
        .name("colour")
        .description("Colour of lush cave chunks on the map.")
        .defaultValue(new SettingColor(60, 220, 90, 110))
        .build());

    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
        .name("max-y")
        .description("Only count lush caves at or below this height.")
        .defaultValue(320).range(-64, 320).sliderRange(-64, 320)
        .onChanged(v -> rescan())
        .build());

    /** Lush cave chunks seen this session, per dimension: ChunkPos.toLong to when they were found. */
    private final Map<RegistryKey<World>, Long2LongMap> found = new HashMap<>();
    private boolean layerAdded;

    public LushCaveMap() {
        super(JJsTools.CATEGORY, "lush-cave-map", "Highlights chunks with lush caves on Xaero's minimap and world map. Needs XaeroPlus.");
    }

    @Override
    public void onActivate() {
        if (!XaeroPlusBridge.installed()) {
            error("Needs XaeroPlus installed.");
            toggle();
            return;
        }
        layerAdded = XaeroPlusBridge.addHighlights(LAYER, this::highlights, () -> colour.get().getPacked(), 250);
        if (!layerAdded) {
            error("This XaeroPlus version cannot show the layer. Update XaeroPlus.");
            toggle();
            return;
        }
        rescan();
    }

    @Override
    public void onDeactivate() {
        if (layerAdded) XaeroPlusBridge.removeHighlights(LAYER);
        layerAdded = false;
    }

    private Long2LongMap highlights(RegistryKey<World> dimension) {
        Long2LongMap map = found.get(dimension);
        return map != null ? map : Long2LongMaps.EMPTY_MAP;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        found.clear();
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        check(event.chunk());
    }

    /** Looks again at every loaded chunk, as on turning on or changing the height. */
    private void rescan() {
        if (!isActive() || mc.world == null || mc.player == null) return;
        found.remove(mc.world.getRegistryKey());
        int radius = mc.options.getViewDistance().getValue() + 2;
        int cx = mc.player.getChunkPos().x, cz = mc.player.getChunkPos().z;
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(x, z, false);
                if (chunk != null) check(chunk);
            }
        }
    }

    private void check(WorldChunk chunk) {
        if (chunk == null || mc.world == null) return;
        long key = chunk.getPos().toLong();
        RegistryKey<World> dimension = mc.world.getRegistryKey();
        if (hasLushCaves(chunk)) {
            found.computeIfAbsent(dimension, d -> new Long2LongOpenHashMap()).putIfAbsent(key, System.currentTimeMillis());
        } else {
            Long2LongMap map = found.get(dimension);
            if (map != null) map.remove(key);
        }
    }

    private boolean hasLushCaves(WorldChunk chunk) {
        ChunkSection[] sections = chunk.getSectionArray();
        int bottom = chunk.getBottomY();
        int top = maxY.get();
        for (int i = 0; i < sections.length; i++) {
            if (bottom + (i << 4) > top) break;
            ChunkSection section = sections[i];
            if (section == null) continue;
            if (section.getBiomeContainer().hasAny(biome -> biome.matchesKey(BiomeKeys.LUSH_CAVES))) return true;
        }
        return false;
    }
}
