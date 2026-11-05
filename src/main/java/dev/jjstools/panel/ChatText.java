package dev.jjstools.panel;

import java.util.regex.Pattern;

/**
 * A chat line made plain for reading: colour codes gone, and any timestamp another mod or a
 * client like Meteor's Better Chat has put in front of it taken off, so "[12:04] Name whispers:
 * hi" reads the same as "Name whispers: hi".
 */
final class ChatText {
    private static final Pattern CODES = Pattern.compile("(?i)§[0-9a-fk-or]");
    private static final Pattern STAMP = Pattern.compile("^\\s*[\\[<(]?\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s?[AaPp][Mm])?[\\]>)]?\\s*");

    private ChatText() {}

    static String clean(String line) {
        if (line == null) return "";
        String s = CODES.matcher(line).replaceAll("").strip();
        for (int i = 0; i < 3; i++) {
            String next = STAMP.matcher(s).replaceFirst("");
            if (next.equals(s)) break;
            s = next;
        }
        return s.strip();
    }
}
