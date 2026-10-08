package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.resource.Resource;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCStdlib;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Plays a sound when someone whispers/PMs you or mentions you in chat.
 *
 * Whispers are spotted by colour (2b2t shows them in light purple with no "whispers" text). Your own outgoing whispers are ignored: when you send /msg, /pm, /w, /t, /r,
 * /reply, /whisper, /wsp or /tell, the text you sent is remembered for a few seconds and the
 * purple line echoing it back is skipped.
 *
 * The sound can be your own .mp3, .ogg or .wav. It is copied into
 * .minecraft/meteor-client/jjs-tools/sounds/ so it keeps working if the original is moved, and is
 * played outside Minecraft's sound system (which can only play built-in sounds).
 */
public class ChatNotifications extends Module {
    private static final File SOUND_DIR = new File(MeteorClient.FOLDER, "jjs-tools/sounds");
    private static final String CACHE_NAME = "chat-notifications";
    private static final String[] FORMATS = {"mp3", "ogg", "wav"};
    private static final Set<String> WHISPER_COMMANDS = Set.of("msg", "pm", "w", "t", "whisper", "wsp", "tell", "m");
    private static final Set<String> REPLY_COMMANDS = Set.of("r", "reply");
    private static final long OUTGOING_MEMORY_MS = 10_000;

    private final SettingGroup sgTriggers = settings.createGroup("Triggers");
    private final SettingGroup sgSound = settings.createGroup("Sound");

    private final Setting<Boolean> whispers = sgTriggers.add(new BoolSetting.Builder()
        .name("whispers")
        .description("Play when someone whispers or PMs you.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> colourWhispers = sgTriggers.add(new BoolSetting.Builder()
        .name("detect-by-colour")
        .description("Treat chat lines that are mostly whisper-colour as whispers. 2b2t shows whispers in light purple.")
        .defaultValue(true)
        .visible(whispers::get)
        .build()
    );

    private final Setting<SettingColor> whisperColour = sgTriggers.add(new ColorSetting.Builder()
        .name("whisper-colour")
        .description("The colour your server uses for whispers. Alpha is ignored.")
        .defaultValue(new SettingColor(255, 85, 255)) // Minecraft's light purple
        .visible(() -> whispers.get() && colourWhispers.get())
        .build()
    );


    private final Setting<Boolean> mentions = sgTriggers.add(new BoolSetting.Builder()
        .name("mentions")
        .description("Play when your username is said in chat as a whole word. Your own messages are ignored.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<String>> keywords = sgTriggers.add(new StringListSetting.Builder()
        .name("keywords")
        .description("Extra words that also count as a mention, like a nickname.")
        .visible(mentions::get)
        .build()
    );

    private final Setting<Boolean> ignoreClientMessages = sgTriggers.add(new BoolSetting.Builder()
        .name("ignore-client-messages")
        .description("Skip lines printed by other mods rather than sent by the server. Baritone and friends print in the same light purple that whispers use, which sets the sound off.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<String>> ignoredPrefixes = sgTriggers.add(new StringListSetting.Builder()
        .name("ignored-prefixes")
        .description("Lines starting with any of these never trigger the sound. Matched after colour codes are stripped, ignoring case.")
        .defaultValue(List.of("[baritone]", "baritone", "[meteor]", "[xaero", "[litematica]"))
        .visible(ignoreClientMessages::get)
        .build()
    );

    private final Setting<Boolean> friendMessages = sgTriggers.add(new BoolSetting.Builder()
        .name("friend-messages")
        .description("Play when one of your Meteor friends says something in public chat.")
        .defaultValue(false)
        .build()
    );


    private boolean syncingToggles;

    private final Setting<Boolean> customSound = sgSound.add(new BoolSetting.Builder()
        .name("custom-sound")
        .description("Play your own sound file. Turning this on turns note-block-pling off, and the other way round.")
        .defaultValue(false)
        .onChanged(v -> syncToggles(true, v))
        .build()
    );

    private final Setting<Boolean> notePling = sgSound.add(new BoolSetting.Builder()
        .name("note-block-pling")
        .description("Play a note block pling. Turning this on turns custom-sound off, and the other way round.")
        .defaultValue(true)
        .onChanged(v -> syncToggles(false, v))
        .build()
    );

    private final Setting<String> soundFile = sgSound.add(new StringSetting.Builder()
        .name("sound-file")
        .description("Full path to an .mp3, .ogg or .wav file.")
        .defaultValue("")
        .wide()
        .visible(this::customOn)
        .onChanged(v -> loadSound(false, false))
        .build()
    );

    private final Setting<Integer> semitones = sgSound.add(new IntSetting.Builder()
        .name("semitones")
        .description("Pitch shift in semitones, for the pling or your own sound. 0 is normal, +12 is one octave up, -12 one octave down.")
        .defaultValue(0)
        .range(-12, 12)
        .sliderRange(-12, 12)
        .build()
    );

    private final Setting<Integer> volume = sgSound.add(new IntSetting.Builder()
        .name("volume")
        .description("Volume in percent.")
        .defaultValue(100)
        .range(0, 100)
        .sliderRange(0, 100)
        .build()
    );

    private final Setting<Integer> cooldown = sgSound.add(new IntSetting.Builder()
        .name("cooldown")
        .description("Minimum milliseconds between sounds. 0: every ping plays, and a new one cuts off the one still playing.")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 5000)
        .build()
    );

    private final ExecutorService audio = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "JJ's Tools Chat Notifier");
        t.setDaemon(true);
        return t;
    });

    private volatile byte[] pcm;        // decoded 16-bit little-endian PCM, or null for the default sound
    private volatile AudioFormat format;
    private volatile byte[] plingPcm;
    private volatile AudioFormat plingFormat;
    private long lastPlayed;
    private volatile SourceDataLine currentLine;
    private final java.util.concurrent.atomic.AtomicInteger playId = new java.util.concurrent.atomic.AtomicInteger();

    /** Text of whispers you sent recently, lower case, with the time sent. */
    private final ArrayDeque<Object[]> outgoing = new ArrayDeque<>();

    public ChatNotifications() {
        super(JJsTools.CATEGORY, "chat-notifications", "Plays a sound when someone whispers to you or mentions you in chat. Setup notes and a preview button are at the bottom of the settings.");
    }

    /** Keeps exactly one of custom-sound / note-block-pling on. */
    private void syncToggles(boolean changedCustom, boolean value) {
        if (syncingToggles) return;
        syncingToggles = true;
        try {
            if (changedCustom) this.notePling.set(!value);
            else this.customSound.set(!value);
        } finally {
            syncingToggles = false;
        }
    }

    private boolean customOn() {
        return this.customSound != null && this.customSound.get();
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        list.add(theme.label("Setup", true));
        list.add(theme.label("1. Pick note-block-pling, or custom-sound and paste the full path of an"));
        list.add(theme.label("   .mp3, .ogg or .wav into sound-file, e.g. C:\\Users\\You\\Music\\ping.mp3"));
        list.add(theme.label("   (it gets copied, so you can move it after)."));
        list.add(theme.label("2. Set semitones for the pitch, then press Preview to check it."));
        list.add(theme.label("Whispers are found by colour (purple on 2b2t). Change whisper-colour for other servers."));

        WButton preview = list.add(theme.button("Preview sound")).widget();
        preview.action = () -> loadSound(true, true);
        return list;
    }

    @Override
    public void onActivate() {
        if (pcm == null) loadSound(false, false);
    }

    // Outgoing whispers (called from ClientPlayNetworkHandlerMixin)

    public static void onCommandSent(String command) {
        Modules modules = Modules.get();
        if (modules == null) return;
        ChatNotifications module = modules.get(ChatNotifications.class);
        if (module != null && module.isActive()) module.rememberOutgoing(command);
    }

    private void rememberOutgoing(String command) {
        String[] parts = command.trim().split("\\s+", 3);
        if (parts.length == 0) return;
        String cmd = parts[0].toLowerCase(Locale.ROOT);

        String text = null;
        if (REPLY_COMMANDS.contains(cmd)) text = command.trim().substring(parts[0].length()).trim();
        else if (WHISPER_COMMANDS.contains(cmd) && parts.length == 3) text = parts[2].trim();

        if (text == null || text.isEmpty()) return;
        outgoing.addLast(new Object[]{text.toLowerCase(Locale.ROOT), System.currentTimeMillis()});
        while (outgoing.size() > 20) outgoing.pollFirst();
    }

    /** True (and forgotten) if this line is the echo of a whisper you sent. */
    private boolean isOwnWhisper(String lower) {
        long now = System.currentTimeMillis();
        Iterator<Object[]> it = outgoing.iterator();
        while (it.hasNext()) {
            Object[] o = it.next();
            if (now - (long) o[1] > OUTGOING_MEMORY_MS) {
                it.remove();
                continue;
            }
            if (lower.contains((String) o[0])) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    // Incoming chat

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        if (mc.player == null || event.getMessage() == null) return;

        Text message = event.getMessage();
        String raw = message.getString();
        String line = Formatting.strip(raw);
        if (line == null || line.isBlank()) return;
        String lower = line.toLowerCase(Locale.ROOT);

        if (ignoreClientMessages.get() && isClientMessage(event, lower)) return;

        boolean whisper = isWhisper(message, raw, lower);
        if (whisper && isOwnWhisper(lower)) return; // your own whisper echoed back

        if (whisper || isMention(line, lower) || isFriendMessage(line)) trigger();
    }

    /**
     * A line another mod printed, rather than one the server sent.
     *
     * The whisper test is colour based, and plenty of mods print in the same light purple, so
     * their output was setting the sound off. Baritone answering a command is the obvious case.
     *
     * Two signals: a known prefix, or no indicator at all. Server chat carries a MessageIndicator;
     * a line injected straight into the chat hud by another mod does not.
     */
    private boolean isClientMessage(ReceiveMessageEvent event, String lower) {
        String trimmed = lower.trim();

        for (String prefix : ignoredPrefixes.get()) {
            if (!prefix.isBlank() && trimmed.startsWith(prefix.toLowerCase(Locale.ROOT))) return true;
        }

        return event.getIndicator() == null;
    }

    private boolean isWhisper(Text message, String raw, String lower) {
        if (!whispers.get()) return false;

        return colourWhispers.get() && mostlyWhisperColour(message, raw);
    }

    /** At least half of the visible characters are in the whisper colour. */
    private boolean mostlyWhisperColour(Text message, String raw) {
        SettingColor c = whisperColour.get();
        int want = (c.r << 16) | (c.g << 8) | c.b;
        int[] counts = new int[2]; // [coloured, total]

        message.visit((Style style, String s) -> {
            int visible = s.replaceAll("\\s", "").length();
            counts[1] += visible;
            TextColor color = style.getColor();
            if (color != null && (color.getRgb() & 0xFFFFFF) == want) counts[0] += visible;
            return Optional.empty();
        }, Style.EMPTY);

        if (counts[1] > 0 && counts[0] * 2 >= counts[1]) return true;

        // Servers that send old-style colour codes in the text itself (§d = light purple).
        if (want == 0xFF55FF) {
            String trimmed = raw.trim();
            return trimmed.startsWith("§d") || trimmed.startsWith("§r§d");
        }
        return false;
    }

    private boolean isMention(String line, String lower) {
        if (!mentions.get()) return false;

        String name = mc.player.getName().getString();
        String nameLower = name.toLowerCase(Locale.ROOT);

        // Skip your own chat lines: "<name> ...", "name: ...", "[name] ...".
        if (lower.startsWith("<" + nameLower + ">") || lower.startsWith(nameLower + ":") || lower.startsWith("[" + nameLower + "]")) {
            return false;
        }

        if (containsWord(line, name)) return true;
        for (String k : keywords.get()) {
            if (!k.isBlank() && containsWord(line, k.trim())) return true;
        }
        return false;
    }

    /** Public chat line ("<name> message") from someone on your Meteor friends list. */
    private boolean isFriendMessage(String line) {
        if (!friendMessages.get()) return false;
        java.util.regex.Matcher m = CHAT_SENDER.matcher(line);
        if (!m.find()) return false;
        String sender = m.group(1);
        if (sender.equalsIgnoreCase(mc.player.getName().getString())) return false;
        return Friends.get().get(sender) != null;
    }

    private static final Pattern CHAT_SENDER = Pattern.compile("^<([A-Za-z0-9_]{1,16})>");

    private static boolean containsWord(String text, String word) {
        return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(word) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE)
            .matcher(text).find();
    }

    private void trigger() {
        long now = System.currentTimeMillis();
        if (now - lastPlayed < cooldown.get()) return;
        lastPlayed = now;
        play();
    }

    // Playback

    private void play() {
        float vol = volume.get() / 100f;
        if (vol <= 0) return;
        int shift = semitones.get();
        boolean custom = customOn();

        // A new ping cuts off the one still playing, so the newest always plays straight away.
        int id = playId.incrementAndGet();
        SourceDataLine playing = currentLine;
        if (playing != null) {
            try {
                playing.stop();
                playing.flush();
                playing.close();
            } catch (Throwable ignored) {
            }
        }

        audio.execute(() -> {
            byte[] data;
            AudioFormat fmt;
            if (custom && pcm != null) {
                data = pcm;
                fmt = format;
            } else {
                if (plingPcm == null) loadPling();
                data = plingPcm;
                fmt = plingFormat;
            }

            if (data == null || fmt == null) {
                // Could not read the pling file: use Minecraft's own sound instead.
                float pitch = (float) Math.max(0.5, Math.min(2.0, Math.pow(2, shift / 12.0)));
                mc.execute(() -> {
                    if (mc.player != null) mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), vol, pitch);
                });
                return;
            }

            if (id != playId.get()) return; // a newer ping came in while this one waited
            byte[] out = scale(pitchShift(data, fmt.getChannels(), shift), vol);
            SourceDataLine line = null;
            try {
                line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, fmt));
                line.open(fmt);
                currentLine = line;
                line.start();
                line.write(out, 0, out.length);
                line.drain();
            } catch (Throwable t) {
                if (id == playId.get()) JJsTools.LOG.warn("Chat Notifier could not play its sound: {}", t.toString());
            } finally {
                if (currentLine == line) currentLine = null;
                if (line != null) line.close();
            }
        });
    }

    /**
     * Shifts pitch by resampling, the same way Minecraft pitches sounds: higher is also shorter,
     * lower is also longer. Linear interpolation between samples, per channel.
     */
    private static byte[] pitchShift(byte[] data, int channels, int semitones) {
        if (semitones == 0) return data;
        double ratio = Math.pow(2, semitones / 12.0);
        int frames = data.length / (2 * channels);
        int outFrames = (int) (frames / ratio);
        byte[] out = new byte[outFrames * channels * 2];

        for (int f = 0; f < outFrames; f++) {
            double pos = f * ratio;
            int i0 = (int) pos;
            int i1 = Math.min(i0 + 1, frames - 1);
            double frac = pos - i0;
            for (int c = 0; c < channels; c++) {
                int a = sample(data, i0 * channels + c);
                int b = sample(data, i1 * channels + c);
                int s = (int) Math.round(a + (b - a) * frac);
                int o = (f * channels + c) * 2;
                out[o] = (byte) s;
                out[o + 1] = (byte) (s >> 8);
            }
        }
        return out;
    }

    private static int sample(byte[] data, int index) {
        int i = index * 2;
        return (short) ((data[i] & 0xFF) | (data[i + 1] << 8));
    }

    /** Reads the vanilla note block pling (minecraft:sounds/note/pling.ogg) through the resource manager. */
    private void loadPling() {
        try {
            Optional<Resource> resource = mc.getResourceManager().getResource(Identifier.ofVanilla("sounds/note/pling.ogg"));
            if (resource.isEmpty()) return;

            byte[] bytes;
            try (InputStream in = resource.get().getInputStream()) {
                bytes = in.readAllBytes();
            }

            ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                buffer.put(bytes).flip();
                IntBuffer channels = stack.mallocInt(1);
                IntBuffer rate = stack.mallocInt(1);
                ShortBuffer samples = STBVorbis.stb_vorbis_decode_memory(buffer, channels, rate);
                if (samples == null) return;
                try {
                    plingFormat = new AudioFormat(rate.get(0), 16, channels.get(0), true, false);
                    plingPcm = toBytes(samples);
                } finally {
                    LibCStdlib.free(samples);
                }
            } finally {
                MemoryUtil.memFree(buffer);
            }
        } catch (Throwable t) {
            JJsTools.LOG.warn("Chat Notifier could not read the note block pling: {}", t.toString());
        }
    }

    private static byte[] toBytes(ShortBuffer samples) {
        byte[] out = new byte[samples.remaining() * 2];
        for (int i = 0, n = samples.remaining(); i < n; i++) {
            short s = samples.get(i);
            out[i * 2] = (byte) s;
            out[i * 2 + 1] = (byte) (s >> 8);
        }
        return out;
    }

    private static byte[] scale(byte[] data, float vol) {
        if (vol >= 0.999f) return data;
        byte[] out = new byte[data.length];
        for (int i = 0; i + 1 < data.length; i += 2) {
            int s = (short) ((data[i] & 0xFF) | (data[i + 1] << 8));
            s = Math.round(s * vol);
            out[i] = (byte) s;
            out[i + 1] = (byte) (s >> 8);
        }
        return out;
    }

    // Loading

    /**
     * Copies the chosen file into the cache (when it exists) and decodes the cached copy.
     * Runs on the audio thread. report: say what went wrong in chat (only for the preview button,
     * since sound-file changes on every keystroke while typing).
     */
    private void loadSound(boolean report, boolean playAfter) {
        String path = soundFile.get() == null ? "" : soundFile.get().trim().replace("\"", "");
        audio.execute(() -> {
            try {
                File cached = null;

                if (!path.isEmpty()) {
                    File src = new File(path);
                    String ext = extension(src.getName());
                    if (src.isFile() && isSupported(ext)) {
                        SOUND_DIR.mkdirs();
                        cached = new File(SOUND_DIR, CACHE_NAME + "." + ext);
                        if (!src.getCanonicalFile().equals(cached.getCanonicalFile())) {
                            clearCache();
                            Files.copy(src.toPath(), cached.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        }
                    } else {
                        cached = findCached(); // original moved or deleted: keep using the cached copy
                        if (report) {
                            String msg = cached != null
                                ? "Sound file not found, using the copy saved earlier."
                                : "Sound file not found, or not .mp3/.ogg/.wav: " + path;
                            mc.execute(() -> info(msg));
                        }
                    }
                }

                switch (cached == null ? "" : extension(cached.getName())) {
                    case "mp3" -> decodeMp3(cached);
                    case "ogg" -> decodeOgg(cached);
                    case "wav" -> decodeWav(cached);
                    default -> {
                        pcm = null;
                        format = null;
                    }
                }
            } catch (Throwable t) {
                pcm = null;
                format = null;
                if (report) mc.execute(() -> warning("Could not load the sound file (" + t.getMessage() + "). Using the default sound."));
            }

            if (playAfter) play();
        });
    }

    private void decodeMp3(File file) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int rate = -1;
        int channels = -1;

        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            Bitstream bitstream = new Bitstream(in);
            Decoder decoder = new Decoder();
            try {
                Header header;
                while ((header = bitstream.readFrame()) != null) {
                    SampleBuffer buffer = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                    rate = buffer.getSampleFrequency();
                    channels = buffer.getChannelCount();

                    short[] samples = buffer.getBuffer();
                    int length = buffer.getBufferLength();
                    for (int i = 0; i < length; i++) {
                        out.write(samples[i] & 0xFF);
                        out.write((samples[i] >> 8) & 0xFF);
                    }
                    bitstream.closeFrame();
                }
            } finally {
                bitstream.close();
            }
        }

        if (rate <= 0 || channels <= 0) throw new IllegalStateException("not a valid .mp3 file");
        format = new AudioFormat(rate, 16, channels, true, false);
        pcm = out.toByteArray();
    }

    private void decodeOgg(File file) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer channels = stack.mallocInt(1);
            IntBuffer rate = stack.mallocInt(1);
            ShortBuffer samples = STBVorbis.stb_vorbis_decode_filename(file.getAbsolutePath(), channels, rate);
            if (samples == null) throw new IllegalStateException("not a valid .ogg file");

            try {
                byte[] out = new byte[samples.remaining() * 2];
                for (int i = 0, n = samples.remaining(); i < n; i++) {
                    short s = samples.get(i);
                    out[i * 2] = (byte) s;
                    out[i * 2 + 1] = (byte) (s >> 8);
                }
                format = new AudioFormat(rate.get(0), 16, channels.get(0), true, false);
                pcm = out;
            } finally {
                LibCStdlib.free(samples);
            }
        }
    }

    private void decodeWav(File file) throws Exception {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(file)) {
            AudioFormat src = in.getFormat();
            AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, src.getSampleRate(), 16,
                src.getChannels(), src.getChannels() * 2, src.getSampleRate(), false);
            try (AudioInputStream pcmIn = AudioSystem.getAudioInputStream(target, in)) {
                byte[] out = pcmIn.readAllBytes();
                format = target;
                pcm = out;
            }
        }
    }

    private static boolean isSupported(String ext) {
        for (String f : FORMATS) if (f.equals(ext)) return true;
        return false;
    }

    private static File findCached() {
        for (String ext : FORMATS) {
            File f = new File(SOUND_DIR, CACHE_NAME + "." + ext);
            if (f.isFile()) return f;
        }
        return null;
    }

    private static void clearCache() {
        for (String ext : FORMATS) new File(SOUND_DIR, CACHE_NAME + "." + ext).delete();
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
