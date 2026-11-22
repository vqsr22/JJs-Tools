package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import dev.jjstools.util.XaeroPlusBridge;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongMaps;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Shows lush caves on Xaero's minimap and world map, through XaeroPlus.
 *
 * The server sends every chunk's biomes along with its blocks, a few per section, so a chunk with
 * lush caves anywhere under it says so the moment it loads, however deep the cave is and whether
 * or not you have seen it. Each such chunk is highlighted the way XaeroPlus's own Old Biomes and
 * New Chunks layers are, and stays highlighted after it unloads.
 *
 * What is found is kept per server (or singleplayer world) in .minecraft/meteor-client/jjs-tools/
 * lush-caves, saved as you leave, every minute while new chunks come in, and on turning this off,
 * and read back when you join again.
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

    private static final File DIR = new File(MeteorClient.FOLDER, "jjs-tools/lush-caves");

    /** Lush cave chunks found on the server in loadedFor, per dimension: ChunkPos.toLong to when they were found. */
    private final Map<RegistryKey<World>, Long2LongMap> found = new HashMap<>();
    private boolean layerAdded;
    /** The server the chunks in found are from, or null before any is loaded. */
    private String loadedFor;
    private boolean dirty;
    private int sinceSave;

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
        ensureLoaded();
        rescan();
    }

    @Override
    public void onDeactivate() {
        if (layerAdded) XaeroPlusBridge.removeHighlights(LAYER);
        layerAdded = false;
        save();
    }

    private Long2LongMap highlights(RegistryKey<World> dimension) {
        Long2LongMap map = found.get(dimension);
        return map != null ? map : Long2LongMaps.EMPTY_MAP;
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        ensureLoaded();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        save();
        found.clear();
        loadedFor = null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (dirty && ++sinceSave >= 1200) save();
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        check(event.chunk());
    }

    /** Looks again at every loaded chunk, as on turning on or changing the height. */
    private void rescan() {
        if (!isActive() || mc.world == null || mc.player == null) return;
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
        ensureLoaded();
        long key = chunk.getPos().toLong();
        RegistryKey<World> dimension = mc.world.getRegistryKey();
        if (hasLushCaves(chunk)) {
            Long2LongMap map = found.computeIfAbsent(dimension, d -> new Long2LongOpenHashMap());
            if (!map.containsKey(key)) {
                map.put(key, System.currentTimeMillis());
                dirty = true;
            }
        } else {
            Long2LongMap map = found.get(dimension);
            if (map != null && map.containsKey(key)) {
                map.remove(key);
                dirty = true;
            }
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

    // Saving

    /** The server (or singleplayer world) you are on, as StorageMemory names it, or null if none. */
    private String server() {
        if (mc.getCurrentServerEntry() != null) return mc.getCurrentServerEntry().address.toLowerCase(Locale.ROOT);
        if (mc.isInSingleplayer() && mc.getServer() != null) return "singleplayer:" + mc.getServer().getSaveProperties().getLevelName();
        return null;
    }

    private static File file(String server) {
        return new File(DIR, server.replaceAll("[^A-Za-z0-9._-]", "_") + ".bin");
    }

    /** Reads the chunks found on this server before, when on a server other than the one loaded. */
    private void ensureLoaded() {
        String server = server();
        if (server == null || server.equals(loadedFor)) return;
        save();
        found.clear();
        loadedFor = server;
        File f = file(server);
        if (!f.isFile()) return;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(f)))) {
            if (in.readInt() != 1) return;
            int dims = in.readInt();
            for (int d = 0; d < dims; d++) {
                RegistryKey<World> dimension = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(in.readUTF()));
                int n = in.readInt();
                Long2LongMap map = new Long2LongOpenHashMap(n);
                for (int i = 0; i < n; i++) map.put(in.readLong(), in.readLong());
                found.put(dimension, map);
            }
        } catch (Exception e) {
            JJsTools.LOG.warn("Could not read saved lush caves for {}", server, e);
        }
    }

    private void save() {
        sinceSave = 0;
        if (!dirty || loadedFor == null) return;
        dirty = false;
        File f = file(loadedFor);
        File tmp = new File(f.getPath() + ".tmp");
        try {
            DIR.mkdirs();
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(tmp)))) {
                out.writeInt(1);
                out.writeInt(found.size());
                for (Map.Entry<RegistryKey<World>, Long2LongMap> e : found.entrySet()) {
                    out.writeUTF(e.getKey().getValue().toString());
                    out.writeInt(e.getValue().size());
                    for (Long2LongMap.Entry c : e.getValue().long2LongEntrySet()) {
                        out.writeLong(c.getLongKey());
                        out.writeLong(c.getLongValue());
                    }
                }
            }
            java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            JJsTools.LOG.warn("Could not save lush caves for {}", loadedFor, e);
        }
    }
}
