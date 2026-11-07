package dev.jjstools.panel;

import dev.jjstools.JJsTools;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.BufferUtils;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The fonts the panel can be drawn in, as monkeytype offers them.
 *
 * Roboto Mono comes with JJ's Tools. The others are all open-licensed and are downloaded once,
 * the first time you pick one, from the font's own repository (mostly Google Fonts' on GitHub),
 * and kept in .minecraft/jjs-tools/fonts. Until a download finishes the panel stays in the font
 * it was in.
 */
public final class PanelFonts {
    /** A font: its name as shown, and where to fetch it from under raw.githubusercontent.com, or null if it comes with JJ's Tools. */
    public record Font(String id, String path) {}

    /** Minecraft's own font. */
    public static final String MINECRAFT = "minecraft";
    /** Whatever font is set in Meteor's Config. */
    public static final String METEOR = "meteor";
    public static final String DEFAULT = "roboto mono";

    public static final List<Font> ALL = List.of(
        new Font("roboto mono", null),
        new Font("atkinson hyperlegible", "google/fonts/main/ofl/atkinsonhyperlegible/AtkinsonHyperlegible-Regular.ttf"),
        new Font("cascadia mono", "google/fonts/main/ofl/cascadiamono/CascadiaMono%5Bwght%5D.ttf"),
        new Font("comfortaa", "google/fonts/main/ofl/comfortaa/Comfortaa%5Bwght%5D.ttf"),
        new Font("coming soon", "google/fonts/main/apache/comingsoon/ComingSoon-Regular.ttf"),
        new Font("commit mono", "eigilnikolajsen/commit-mono/main/src/fonts/fontlab/CommitMonoV143-400Regular.otf"),
        new Font("courier prime", "google/fonts/main/ofl/courierprime/CourierPrime-Regular.ttf"),
        new Font("fira code", "google/fonts/main/ofl/firacode/FiraCode%5Bwght%5D.ttf"),
        new Font("geist", "google/fonts/main/ofl/geist/Geist%5Bwght%5D.ttf"),
        new Font("geist mono", "google/fonts/main/ofl/geistmono/GeistMono%5Bwght%5D.ttf"),
        new Font("hack", "source-foundry/Hack/master/build/ttf/Hack-Regular.ttf"),
        new Font("ibm plex mono", "google/fonts/main/ofl/ibmplexmono/IBMPlexMono-Regular.ttf"),
        new Font("ibm plex sans", "google/fonts/main/ofl/ibmplexsans/IBMPlexSans%5Bwdth,wght%5D.ttf"),
        new Font("inconsolata", "google/fonts/main/ofl/inconsolata/Inconsolata%5Bwdth,wght%5D.ttf"),
        new Font("inter tight", "google/fonts/main/ofl/intertight/InterTight%5Bwght%5D.ttf"),
        new Font("itim", "google/fonts/main/ofl/itim/Itim-Regular.ttf"),
        new Font("jetbrains mono", "google/fonts/main/ofl/jetbrainsmono/JetBrainsMono%5Bwght%5D.ttf"),
        new Font("kanit", "google/fonts/main/ofl/kanit/Kanit-Regular.ttf"),
        new Font("lalezar", "google/fonts/main/ofl/lalezar/Lalezar-Regular.ttf"),
        new Font("lato", "google/fonts/main/ofl/lato/Lato-Regular.ttf"),
        new Font("lexend deca", "google/fonts/main/ofl/lexenddeca/LexendDeca%5Bwght%5D.ttf"),
        new Font("mononoki", "madmalik/mononoki/main/export/mononoki-Regular.ttf"),
        new Font("montserrat", "google/fonts/main/ofl/montserrat/Montserrat%5Bwght%5D.ttf"),
        new Font("noto naskh arabic", "google/fonts/main/ofl/notonaskharabic/NotoNaskhArabic%5Bwght%5D.ttf"),
        new Font("noto sans lao", "google/fonts/main/ofl/notosanslao/NotoSansLao%5Bwdth,wght%5D.ttf"),
        new Font("nunito", "google/fonts/main/ofl/nunito/Nunito%5Bwght%5D.ttf"),
        new Font("open dyslexic", "antijingoist/opendyslexic/master/compiled/OpenDyslexic-Regular.otf"),
        new Font("overpass mono", "google/fonts/main/ofl/overpassmono/OverpassMono%5Bwght%5D.ttf"),
        new Font("oxygen", "google/fonts/main/ofl/oxygen/Oxygen-Regular.ttf"),
        new Font("parkinsans", "google/fonts/main/ofl/parkinsans/Parkinsans%5Bwght%5D.ttf"),
        new Font("roboto", "google/fonts/main/ofl/roboto/Roboto%5Bwdth,wght%5D.ttf"),
        new Font("sarabun", "google/fonts/main/ofl/sarabun/Sarabun-Regular.ttf"),
        new Font("source code pro", "google/fonts/main/ofl/sourcecodepro/SourceCodePro%5Bwght%5D.ttf"),
        new Font("space mono", "google/fonts/main/ofl/spacemono/SpaceMono-Regular.ttf"),
        new Font("space grotesk", "google/fonts/main/ofl/spacegrotesk/SpaceGrotesk%5Bwght%5D.ttf"),
        new Font("titillium web", "google/fonts/main/ofl/titilliumweb/TitilliumWeb-Regular.ttf"),
        new Font("ubuntu", "google/fonts/main/ufl/ubuntu/Ubuntu-Regular.ttf"),
        new Font("ubuntu mono", "google/fonts/main/ufl/ubuntumono/UbuntuMono-Regular.ttf")
    );

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Map<String, String> STATE = new ConcurrentHashMap<>();

    private PanelFonts() {}

    public static Font find(String id) {
        for (Font f : ALL) if (f.id().equals(id)) return f;
        return null;
    }

    /** Everything the font page lists: minecraft, meteor, then the fonts by name. */
    public static List<String> choices() {
        List<String> list = new ArrayList<>();
        list.add(MINECRAFT);
        list.add(METEOR);
        List<String> names = new ArrayList<>();
        for (Font f : ALL) names.add(f.id());
        names.sort(String::compareTo);
        list.addAll(names);
        return list;
    }

    private static Path cached(Font f) {
        String ext = f.path().endsWith(".otf") ? ".otf" : ".ttf";
        return FabricLoader.getInstance().getGameDir().resolve("jjs-tools").resolve("fonts").resolve(f.id().replace(' ', '-') + ext);
    }

    /** Whether a font is ready to use, still downloading, or could not be fetched. */
    public static String state(String id) {
        Font f = find(id);
        if (f == null || f.path() == null) return "ready";
        if (Files.exists(cached(f))) return "ready";
        return STATE.getOrDefault(id, "not downloaded");
    }

    /**
     * The font's file, ready for the font reader, or null while it is still being downloaded
     * (the download is started the first time it is asked for).
     */
    public static ByteBuffer data(String id) {
        Font f = find(id);
        if (f == null) return null;
        try {
            byte[] bytes;
            if (f.path() == null) {
                try (InputStream in = PanelFonts.class.getResourceAsStream("/assets/jjs-tools/fonts/RobotoMono.ttf")) {
                    if (in == null) return null;
                    bytes = in.readAllBytes();
                }
            } else {
                Path file = cached(f);
                if (!Files.exists(file)) {
                    download(f);
                    return null;
                }
                bytes = Files.readAllBytes(file);
            }
            ByteBuffer buffer = BufferUtils.createByteBuffer(bytes.length);
            buffer.put(bytes).flip();
            return buffer;
        } catch (Exception e) {
            JJsTools.LOG.warn("Couldn't read the font " + id, e);
            return null;
        }
    }

    /**
     * The font as a file on disk, for things that read fonts from files such as Meteor's own
     * font list: the download, or the bundled Roboto Mono written out next to the downloads.
     * Null while it is still being fetched (the download is started the first time it is asked for).
     */
    public static Path fileFor(String id) {
        Font f = find(id);
        if (f == null) return null;
        try {
            if (f.path() == null) {
                Path file = FabricLoader.getInstance().getGameDir().resolve("jjs-tools").resolve("fonts").resolve(f.id().replace(' ', '-') + ".ttf");
                if (!Files.exists(file)) {
                    try (InputStream in = PanelFonts.class.getResourceAsStream("/assets/jjs-tools/fonts/RobotoMono.ttf")) {
                        if (in == null) return null;
                        Files.createDirectories(file.getParent());
                        Files.write(file, in.readAllBytes());
                    }
                }
                return file;
            }
            Path file = cached(f);
            if (Files.exists(file)) return file;
            download(f);
            return null;
        } catch (Exception e) {
            JJsTools.LOG.warn("Couldn't write out the font " + id, e);
            return null;
        }
    }

    /** Picking a font that failed to download tries it again. */
    public static void retry(String id) {
        STATE.remove(id, "failed");
    }

    private static void download(Font f) {
        // One try at a time, and none again after failing until it is picked again.
        if (STATE.putIfAbsent(f.id(), "downloading") != null) return;
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://raw.githubusercontent.com/" + f.path()))
            .timeout(Duration.ofSeconds(60)).header("User-Agent", "JJs-Tools").build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).whenComplete((r, error) -> {
            try {
                if (error != null || r.statusCode() != 200 || r.body().length < 1000) throw new IllegalStateException(error != null ? error.getMessage() : "status " + r.statusCode());
                Path file = cached(f);
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".part");
                Files.write(tmp, r.body());
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                STATE.remove(f.id());
            } catch (Exception e) {
                STATE.put(f.id(), "failed");
                JJsTools.LOG.warn("Couldn't download the font " + f.id() + ": " + e.getMessage());
            }
        });
    }
}
