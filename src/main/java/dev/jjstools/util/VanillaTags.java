package dev.jjstools.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What an item tag contains according to the game itself, rather than according to the server.
 *
 * The client normally learns its tags from the server. Behind a translated connection that can
 * come through empty, and anything that asks a tag what it holds, the recipe book above all, is
 * then answered with nothing. The game jar carries the vanilla data pack for singleplayer, tags
 * included, so the real answer is sitting right there to be read.
 *
 * Tags that include other tags are followed all the way down. Read once per tag and kept.
 */
public final class VanillaTags {
    private static final Map<Identifier, Set<Item>> CACHE = new HashMap<>();

    private VanillaTags() {}

    /** The items in this tag as vanilla defines it, or nothing if vanilla has no such tag. */
    public static Set<Item> items(Identifier tag) {
        return resolve(tag, new HashSet<>());
    }

    private static Set<Item> resolve(Identifier tag, Set<Identifier> visiting) {
        Set<Item> known = CACHE.get(tag);
        if (known != null) return known;

        // A tag that includes itself, however indirectly, would otherwise never finish.
        if (!visiting.add(tag)) return Collections.emptySet();

        Set<Item> items = new HashSet<>();
        String path = "data/" + tag.getNamespace() + "/tags/item/" + tag.getPath() + ".json";

        try (InputStream in = MinecraftClient.class.getClassLoader().getResourceAsStream(path)) {
            if (in != null) {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonArray values = root.getAsJsonArray("values");

                if (values != null) {
                    for (JsonElement value : values) {
                        String id = value.isJsonObject()
                            ? value.getAsJsonObject().get("id").getAsString()
                            : value.getAsString();

                        if (id.startsWith("#")) {
                            Identifier inner = Identifier.tryParse(id.substring(1));
                            if (inner != null) items.addAll(resolve(inner, visiting));
                        }
                        else {
                            Identifier itemId = Identifier.tryParse(id);
                            if (itemId == null) continue;

                            Registries.ITEM.getEntry(itemId).map(RegistryEntry::value)
                                .filter(item -> item != Items.AIR)
                                .ifPresent(items::add);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // An unreadable tag is an empty one, which is the answer there already was.
        }

        Set<Item> result = Collections.unmodifiableSet(items);
        CACHE.put(tag, result);
        return result;
    }
}
