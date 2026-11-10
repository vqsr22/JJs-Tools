package dev.jjstools.panel.panels;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.jjstools.panel.Panel;
import dev.jjstools.panel.TextBox;
import dev.jjstools.panel.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.PlayerSkinDrawer;
//#if MC >= 1.21.9
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.entity.player.SkinTextures;
//#else
//$$ import net.minecraft.client.util.SkinTextures;
//#endif
import net.minecraft.item.Items;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Looks a player up on 2b2t.vc: when they were first and last seen, playtime, kills, deaths,
 * joins, chats and whether they have priority queue, laid out like the 2b2t.vc Discord bot's card.
 *
 * Uses 2b2t.vc's public API (api.2b2t.vc/stats/player). The request runs off the game thread,
 * and the answer is drawn when it arrives.
 */
public class LookupPanel extends Panel {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy h:mm a", Locale.ENGLISH);

    /** One player's stats, as 2b2t.vc gave them. */
    private record Stats(String name, Supplier<SkinTextures> skin, OffsetDateTime firstSeen, OffsetDateTime lastSeen,
                         long playtime, long playtimeMonth, int kills, int deaths, int joins, int chats, boolean prio) {}

    // Kept for as long as the game runs, not just while the panel is open: the Control Panel
    // makes its windows afresh each time it opens, and a lookup should still be there after.
    private static final TextBox search = new TextBox("username");
    private static volatile Stats stats;
    private static volatile String status = "";
    private static volatile boolean busy;

    public LookupPanel() {
        super("lookup", "lookup", Items.SPYGLASS.getDefaultStack(), 190, 150);
        search.max = 16;
        search.onEnter = LookupPanel::lookUp;
    }

    private static void lookUp() {
        String name = search.text.trim();
        if (busy || name.isEmpty()) return;
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            status = "not a valid username";
            return;
        }
        busy = true;
        status = "looking up " + name + "...";
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.2b2t.vc/stats/player?playerName=" + URLEncoder.encode(name, StandardCharsets.UTF_8)))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", "JJs-Tools")
            .header("Accept", "application/json")
            .GET()
            .build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((r, error) -> {
            try {
                if (error != null) {
                    status = "could not reach 2b2t.vc";
                } else if (r.statusCode() == 204 || r.statusCode() == 404 || r.body() == null || r.body().isBlank()) {
                    stats = null;
                    status = name + " has never been seen on 2b2t";
                } else if (r.statusCode() >= 400) {
                    status = "2b2t.vc said " + r.statusCode();
                } else {
                    stats = parse(name, JsonParser.parseString(r.body()).getAsJsonObject());
                    status = "";
                }
            } catch (Exception e) {
                status = "could not read 2b2t.vc's answer";
            } finally {
                busy = false;
            }
        });
    }

    private static Stats parse(String name, JsonObject o) {
        // The game's own skin cache fetches the skin for the name, and the face shows it once it
        // has; until then it is the default skin.
        //#if MC >= 1.21.9
        var entry = MinecraftClient.getInstance().getPlayerSkinCache().getSupplier(ProfileComponent.ofDynamic(name));
        Supplier<SkinTextures> skin = () -> entry.get().getTextures();
        //#else
        //$$ // Older versions have no cache by name: look the profile up, then fetch its skin.
        //$$ var fetched = new java.util.concurrent.atomic.AtomicReference<SkinTextures>();
        //$$ net.minecraft.block.entity.SkullBlockEntity.fetchProfileByName(name).thenAccept(profile -> profile.ifPresent(p ->
        //$$     MinecraftClient.getInstance().getSkinProvider().fetchSkinTextures(p).thenAccept(t -> t.ifPresent(fetched::set))));
        //$$ Supplier<SkinTextures> skin = () -> {
        //$$     SkinTextures t = fetched.get();
        //$$     return t != null ? t : net.minecraft.client.util.DefaultSkinHelper.getSteve();
        //$$ };
        //#endif
        return new Stats(name, skin, date(o, "firstSeen"), date(o, "lastSeen"),
            num(o, "playtimeSeconds"), num(o, "playtimeSecondsMonth"),
            (int) num(o, "killCount"), (int) num(o, "deathCount"), (int) num(o, "joinCount"), (int) num(o, "chatsCount"),
            o.has("prio") && !o.get("prio").isJsonNull() && o.get("prio").getAsBoolean());
    }

    private static long num(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? 0 : e.getAsLong();
    }

    private static OffsetDateTime date(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return null;
        try {
            return OffsetDateTime.parse(e.getAsString());
        } catch (Exception ex) {
            return null;
        }
    }

    private static String when(OffsetDateTime t) {
        if (t == null) return "never";
        return t.atZoneSameInstant(ZoneId.systemDefault()).format(DATE).toLowerCase(Locale.ROOT);
    }

    /** "1 month, 5 days, 21 hours", counting a month as 30 days, as 2b2t.vc's bot does. */
    private static String duration(long seconds) {
        if (seconds <= 0) return "none";
        long months = seconds / (30L * 86400);
        long days = seconds / 86400 % 30;
        long hours = seconds / 3600 % 24;
        StringBuilder b = new StringBuilder();
        part(b, months, "month");
        part(b, days, "day");
        part(b, hours, "hour");
        if (b.isEmpty()) part(b, Math.max(1, seconds / 60), "minute");
        return b.toString();
    }

    private static void part(StringBuilder b, long n, String unit) {
        if (n <= 0) return;
        if (!b.isEmpty()) b.append(", ");
        b.append(n).append(' ').append(unit).append(n == 1 ? "" : "s");
    }

    @Override
    public void draw(Ui ui, int x, int y, int w, int h) {
        int bh = Math.max(16, ui.lineH() + 7);
        int gap = 3;
        int lw = ui.width("look up") + 24;
        ui.textBox(search, x, y, w - lw - gap, bh);
        ui.button(x + w - lw, y, lw, bh, "look up", !busy && !search.text.isBlank(), LookupPanel::lookUp);
        y += bh + gap + 3;

        Stats s = stats;
        String st = status;
        if (!st.isEmpty()) {
            ui.text(ui.fit(st, w), x, y, busy ? ui.accent() : ui.fgDim());
            y += ui.lineH() + 6;
        }
        if (s == null) return;

        // Laid out in three columns like the bot's card, with their face in the top right.
        int head = Math.min(40, Math.max(24, w / 6));
        PlayerSkinDrawer.draw(ui.ctx, s.skin().get(), x + w - head, y, head);
        ui.added(2);
        int col = (w - 4) / 3;
        int line = ui.lineH() + 2;
        int block = line * 2 + 5;

        field(ui, x, y, col * 3 / 2, "player", s.name(), ui.accent());
        // Online is read from the tab list, so it needs you on a server: 2b2t itself, or 2b2t
        // through a proxy such as ZenithProxy, whose address says nothing about 2b2t.
        int ox = x + col * 3 / 2;
        ui.text("status", ox, y, ui.fg());
        int vy = y + ui.lineH() + 2;
        if (connected()) {
            boolean on = ui.online(s.name());
            int dot = 6;
            ui.dot(ox, vy + (ui.lineH() - dot) / 2, dot, on ? Ui.GREEN : Ui.GREY_DOT);
            ui.text(on ? "online" : "offline", ox + dot + 4, vy, ui.fgDim());
        } else {
            ui.text(ui.fit("join to see", w - head - 6 - (ox - x)), ox, vy, ui.fgDim());
        }
        y += block;
        field(ui, x, y, col * 3 / 2, "first seen", when(s.firstSeen()), ui.fgDim());
        field(ui, x + col * 3 / 2, y, w - col * 3 / 2, "last seen", when(s.lastSeen()), ui.fgDim());
        y += block;
        field(ui, x, y, col * 3 / 2, "playtime", duration(s.playtime()), ui.fgDim());
        field(ui, x + col * 3 / 2, y, w - col * 3 / 2, "playtime last 30 days", duration(s.playtimeMonth()), ui.fgDim());
        y += block;
        String kd = s.deaths() == 0 ? String.valueOf(s.kills()) : String.format(Locale.ROOT, "%.2f", s.kills() / (double) s.deaths());
        field(ui, x, y, col, "kills", Ui.grouped(s.kills()), ui.fgDim());
        field(ui, x + col, y, col, "deaths", Ui.grouped(s.deaths()), ui.fgDim());
        field(ui, x + col * 2, y, w - col * 2, "k/d", kd, ui.fgDim());
        y += block;
        field(ui, x, y, col, "joins", Ui.grouped(s.joins()), ui.fgDim());
        field(ui, x + col, y, col, "chats", Ui.grouped(s.chats()), ui.fgDim());
        field(ui, x + col * 2, y, w - col * 2, "priority queue", s.prio() ? "yes" : "no", ui.fgDim());
    }

    private static boolean connected() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.getNetworkHandler() != null && mc.getCurrentServerEntry() != null;
    }

    private static void field(Ui ui, int x, int y, int w, String label, String value, int colour) {
        ui.text(ui.fit(label, w - 4), x, y, ui.fg());
        ui.text(ui.fit(value, w - 4), x, y + ui.lineH() + 2, colour);
    }
}
