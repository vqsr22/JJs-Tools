package dev.jjstools.panel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import dev.jjstools.JJsTools;
import dev.jjstools.modules.ControlPanel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Talks to a JJ's Tools sync server (server/jjs-sync-server.py) for the stash manager.
 *
 * Every few seconds it sends the containers you have opened since last time, asks the server
 * which stashes changed, and fetches only what changed since the version it last had. All the
 * network work happens off the game thread; what comes back is merged in on the next tick,
 * container by container, newest winning, the same as with a shared folder.
 */
public final class StashSync {
    /** Changes to send: a stash's new settings, if any, and the containers that changed. */
    record Push(String name, String json, List<String> keys, boolean meta) {}

    /** What came back for one stash. */
    record Change(String name, long version, JsonObject meta, Map<String, StashStore.Container> containers, boolean full) {}

    /** Everything one round brought back. */
    record Result(List<Change> changes, Map<String, Long> remote, Map<String, Long> deleted, List<Push> failed, String error) {}

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "JJ's Tools stash sync");
        t.setDaemon(true);
        return t;
    });
    static final ConcurrentLinkedQueue<Result> RESULTS = new ConcurrentLinkedQueue<>();
    private static final List<String> DELETES = new ArrayList<>();

    /** The server's version of each stash we have caught up to, by lower-case name. */
    static final Map<String, Long> known = new HashMap<>();
    private static volatile boolean busy;
    private static long lastRound;
    private static long lastOk;
    private static String lastError;
    private static String connectedTo;

    private StashSync() {}

    static boolean due() {
        if (busy) return false;
        String address = ControlPanel.syncServer();
        String token = ControlPanel.syncToken();
        if (address.isBlank() || token.isBlank()) {
            lastError = "set the server and token";
            return false;
        }
        // A different server or token starts again from nothing.
        String to = address + "|" + token;
        if (!to.equals(connectedTo)) {
            connectedTo = to;
            known.clear();
            lastOk = 0;
        }
        return System.currentTimeMillis() - lastRound >= ControlPanel.syncInterval() * 1000L;
    }

    static void queueDelete(String name) {
        synchronized (DELETES) {
            DELETES.add(name);
        }
    }

    /** Starts a round in the background with these changes to send. */
    static void start(List<Push> pushes) {
        busy = true;
        lastRound = System.currentTimeMillis();
        String base = ControlPanel.syncServer().trim().replaceAll("/+$", "");
        String token = ControlPanel.syncToken().trim();
        Map<String, Long> versions = new HashMap<>(known);
        List<String> deletes;
        synchronized (DELETES) {
            deletes = new ArrayList<>(DELETES);
            DELETES.clear();
        }
        WORKER.execute(() -> {
            try {
                RESULTS.add(round(base, token, pushes, deletes, versions));
            } catch (Throwable t) {
                RESULTS.add(new Result(List.of(), null, null, pushes, t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
            } finally {
                busy = false;
            }
        });
    }

    private static Result round(String base, String token, List<Push> pushes, List<String> deletes, Map<String, Long> versions) throws Exception {
        for (String name : deletes) send("DELETE", base + "/api/stashes/" + enc(name), token, null);
        // If any of this fails, every change is sent again next round; the server ignores
        // anything it already has, so sending twice does no harm.
        for (Push p : pushes) send("POST", base + "/api/stashes/" + enc(p.name()), token, p.json());

        JsonObject list = PanelFiles.GSON.fromJson(send("GET", base + "/api/stashes", token, null), JsonObject.class);
        Map<String, Long> remote = new HashMap<>();
        if (list.has("stashes")) for (Map.Entry<String, JsonElement> e : list.getAsJsonObject("stashes").entrySet()) remote.put(e.getKey(), e.getValue().getAsLong());
        Map<String, Long> deleted = new HashMap<>();
        if (list.has("deleted")) for (Map.Entry<String, JsonElement> e : list.getAsJsonObject("deleted").entrySet()) deleted.put(e.getKey(), e.getValue().getAsLong());

        List<Change> changes = new ArrayList<>();
        for (Map.Entry<String, Long> e : remote.entrySet()) {
            Long have = versions.get(e.getKey().toLowerCase(java.util.Locale.ROOT));
            if (have != null && have >= e.getValue()) continue;
            long since = have == null ? 0 : have;
            JsonObject body = PanelFiles.GSON.fromJson(send("GET", base + "/api/stashes/" + enc(e.getKey()) + "?since=" + since, token, null), JsonObject.class);
            Map<String, StashStore.Container> containers = body.has("containers")
                ? PanelFiles.GSON.fromJson(body.get("containers"), new TypeToken<Map<String, StashStore.Container>>() {}.getType())
                : Map.of();
            JsonObject meta = body.has("meta") ? body.getAsJsonObject("meta") : null;
            changes.add(new Change(e.getKey(), body.get("version").getAsLong(), meta, containers, have == null));
        }
        return new Result(changes, remote, deleted, List.of(), null);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String send(String method, String url, String token, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + token)
            .header("Accept-Encoding", "gzip")
            .header("User-Agent", "JJs-Tools-Sync");
        if (json != null) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
                gz.write(json.getBytes(StandardCharsets.UTF_8));
            }
            b.header("Content-Type", "application/json").header("Content-Encoding", "gzip");
            b.method(method, HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<byte[]> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        byte[] data = r.body();
        if ("gzip".equalsIgnoreCase(r.headers().firstValue("Content-Encoding").orElse(""))) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
                data = in.readAllBytes();
            }
        }
        if (r.statusCode() == 401) throw new Exception("token rejected");
        if (r.statusCode() >= 400 && !(r.statusCode() == 404 && "DELETE".equals(method))) throw new Exception("server said " + r.statusCode());
        return new String(data, StandardCharsets.UTF_8);
    }

    /** Notes how a round went, for the status line. */
    static List<Push> finished(Result r) {
        if (r.error() == null) {
            lastOk = System.currentTimeMillis();
            lastError = null;
            return List.of();
        }
        lastError = r.error();
        JJsTools.LOG.warn("Stash sync: " + r.error());
        return r.failed();
    }

    /** A few words on how syncing is going, for the stash manager's bottom line. */
    public static String status() {
        if (lastError != null) return "sync: " + lastError;
        if (lastOk == 0) return "syncing...";
        long s = (System.currentTimeMillis() - lastOk) / 1000;
        return "synced " + (s < 60 ? s + "s" : (s / 60) + "m") + " ago";
    }
}
