package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cleans up chat messages: green text, the > arrow, and junk tagged onto the end of messages. */
public class ChatTweaks extends Module {
    private static final int GREEN = 0x55FF55;
    private static final TextColor WHITE = TextColor.fromFormatting(Formatting.WHITE);
    private static final Pattern CHAT_NAME = Pattern.compile("^<[^>]{1,32}> ");
    private static final Pattern SUFFIX_JUNK = Pattern.compile("\\s*\\[[^\\[\\]]*]\\s*$");

    private final SettingGroup sgGreen = settings.createGroup("Green Text");
    private final SettingGroup sgJunk = settings.createGroup("Junk Hiders");

    private final Setting<Boolean> noGreenText = sgGreen.add(new BoolSetting.Builder()
        .name("remove-green-text")
        .description("Show green text in white. \"> hello\" in green becomes \"> hello\" in white.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> removeArrow = sgGreen.add(new BoolSetting.Builder()
        .name("remove-arrow")
        .description("Remove the > at the start of green text. \"<Name> > hello\" becomes \"<Name> hello\".")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> suffixJunk = sgJunk.add(new BoolSetting.Builder()
        .name("suffix-junk-hider")
        .description("Hide [ ] blocks at the end of a message, brackets included. \"ez run [971fa2b793ec]\" becomes \"ez run\".")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> suffixName = sgJunk.add(new BoolSetting.Builder()
        .name("suffix-name-hider")
        .description("Hide a client name tagged on with a | at the end of a message: the last | and the text after it. \"ez run | Hackware\" becomes \"ez run\".")
        .defaultValue(true)
        .build()
    );


    private final Setting<Boolean> appendHider = sgJunk.add(new BoolSetting.Builder()
        .name("append-hider")
        .description("Hide a | and everything after it. \"ez run | RusherHack\" becomes \"ez run\".")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> randomSuffix = sgJunk.add(new BoolSetting.Builder()
        .name("random-suffix-hider")
        .description("Hide a last word that is random characters rather than a word, with no bracket or bar in front of it. \"fake it and make it! LYhcTnbxvdeelkmq\" becomes \"fake it and make it!\".")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> randomLength = sgJunk.add(new IntSetting.Builder()
        .name("random-suffix-length")
        .description("Shortest last word the random check will touch. Raise it if a long name or an odd word is being eaten.")
        .visible(randomSuffix::get)
        .min(6)
        .max(32)
        .sliderRange(8, 20)
        .defaultValue(10)
        .build()
    );

    private final Setting<Boolean> hideJunkMessages = sgJunk.add(new BoolSetting.Builder()
        .name("hide-junk-messages")
        .description("Hide the whole message rather than trimming it, whenever any of the hiders above find something. Everything another client has tagged its name or a hash onto disappears.")
        .defaultValue(false)
        .build()
    );

    public ChatTweaks() {
        super(JJsTools.CATEGORY, "chat-tweaks", "Cleans up chat: green text, the > arrow, and junk people's clients add to the end of messages.");
    }


    @EventHandler(priority = EventPriority.HIGH)
    private void onMessage(ReceiveMessageEvent event) {
        Text message = event.getMessage();
        if (message == null) return;

        // Flatten into styled pieces so the text can be cut while keeping colours and click events.
        List<String> parts = new ArrayList<>();
        List<Style> styles = new ArrayList<>();
        message.visit((style, string) -> {
            if (!string.isEmpty()) {
                parts.add(string);
                styles.add(style);
            }
            return Optional.empty();
        }, Style.EMPTY);

        StringBuilder all = new StringBuilder();
        for (String p : parts) all.append(p);
        String plain = all.toString();

        // The message body is everything after "<Name> ", or the whole line for other messages.
        Matcher name = CHAT_NAME.matcher(plain);
        int bodyStart = name.find() ? name.end() : 0;

        int removeFrom = -1, removeTo = -1; // the leading arrow
        int cut = plain.length();           // everything from here on is hidden

        if (removeArrow.get() && plain.startsWith(">", bodyStart)) {
            removeFrom = bodyStart;
            removeTo = bodyStart + 1;
            while (removeTo < plain.length() && plain.charAt(removeTo) == ' ') removeTo++;
        }

        int bodyFrom = removeTo > 0 ? removeTo : bodyStart;
        boolean junkFound = false;

        if (appendHider.get()) {
            int bar = plain.indexOf('|', bodyFrom);
            if (bar > bodyFrom) {
                cut = Math.min(cut, bar);
                junkFound = true;
            }
        }

        // Repeat: "... Hackware [abc]" needs the brackets gone before the name is at the end.
        boolean changed = true;
        boolean nameHidden = false; // only the last "| name" is a suffix name
        while (changed) {
            changed = false;
            String body = plain.substring(bodyFrom, cut);
            if (suffixJunk.get()) {
                Matcher junk = SUFFIX_JUNK.matcher(body);
                if (junk.find() && junk.start() > 0) {
                    cut = bodyFrom + junk.start();
                    junkFound = true;
                    changed = true;
                    continue;
                }
            }
            if (suffixName.get() && !nameHidden) {
                int bar = body.lastIndexOf('|');
                if (bar > 0 && !body.substring(bar + 1).isBlank()) {
                    cut = bodyFrom + bar;
                    nameHidden = true;
                    junkFound = true;
                    changed = true;
                    continue;
                }
            }
            if (randomSuffix.get()) {
                int start = randomSuffixStart(body);
                if (start > 0) {
                    cut = bodyFrom + start;
                    junkFound = true;
                    changed = true;
                }
            }
        }

        if (hideJunkMessages.get() && junkFound) {
            event.cancel();
            return;
        }
        // Drop trailing spaces and separators left behind by a cut.
        while (cut > bodyFrom && " -|»>:".indexOf(plain.charAt(cut - 1)) >= 0 && cut < plain.length()) cut--;
        while (cut > bodyFrom && Character.isWhitespace(plain.charAt(cut - 1))) cut--;

        boolean recolour = noGreenText.get() && (hasGreen(styles) || plain.contains("§a"));
        if (removeFrom < 0 && cut == plain.length() && !recolour) return; // nothing to change

        MutableText out = Text.empty();
        int pos = 0;
        for (int i = 0; i < parts.size(); i++) {
            String p = parts.get(i);
            int start = pos, end = pos + p.length();
            pos = end;

            StringBuilder keep = new StringBuilder();
            for (int c = start; c < end && c < cut; c++) {
                if (c >= removeFrom && c < removeTo) continue;
                keep.append(p.charAt(c - start));
            }
            if (keep.isEmpty()) continue;

            String text = keep.toString();
            Style style = styles.get(i);
            if (noGreenText.get()) {
                text = text.replace("§a", "§f");
                if (isGreen(style)) style = style.withColor(WHITE);
            }
            out.append(Text.literal(text).setStyle(style));
        }
        event.setMessage(out);
    }

    /**
     * Where the trailing gibberish starts in this body, or -1.
     *
     * Only the last word is considered, and only when something comes before it, so a message that
     * is one odd word is left alone. A word is judged on how it is spelled rather than on a list,
     * because the whole point of these is that they are generated fresh every message.
     */
    private int randomSuffixStart(String body) {
        int end = body.length();
        while (end > 0 && Character.isWhitespace(body.charAt(end - 1))) end--;
        if (end == 0) return -1;

        int start = end;
        while (start > 0 && !Character.isWhitespace(body.charAt(start - 1))) start--;
        if (start == 0) return -1;

        return looksRandom(body.substring(start, end)) ? start : -1;
    }

    /**
     * Three things give these away, and any one of them is enough.
     *
     * A capital in the middle of a word nobody capitalised, far too few vowels to pronounce, or a
     * mix of letters and several digits, which is what a hex hash looks like without its brackets.
     * Punctuation rules a word out entirely: a real word can carry it, a generated token does not.
     */
    private boolean looksRandom(String token) {
        if (token.length() < randomLength.get()) return false;

        int innerUpper = 0, lower = 0, digits = 0, vowels = 0;

        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);

            if (c >= 'A' && c <= 'Z') {
                if (i > 0) innerUpper++;
            }
            else if (c >= 'a' && c <= 'z') lower++;
            else if (c >= '0' && c <= '9') digits++;
            else return false;

            if ("aeiouAEIOU".indexOf(c) >= 0) vowels++;
        }

        boolean mixedCase = innerUpper > 0 && lower > 0;
        boolean unsayable = vowels * 20 < token.length() * 3;        // under 15% vowels
        boolean hashLike = digits >= 3 && innerUpper + lower >= 3;

        return mixedCase || unsayable || hashLike;
    }

    private static boolean hasGreen(List<Style> styles) {
        for (Style s : styles) if (isGreen(s)) return true;
        return false;
    }

    private static boolean isGreen(Style style) {
        TextColor colour = style.getColor();
        return colour != null && (colour.getRgb() & 0xFFFFFF) == GREEN;
    }
}
