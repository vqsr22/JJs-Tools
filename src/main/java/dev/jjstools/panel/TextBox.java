package dev.jjstools.panel;

import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

/**
 * A line of text you can type into: the search boxes, the message box, the preset editor.
 *
 * Kept as plain state, the text and where the cursor is, and drawn by Ui.textBox. Typing,
 * deleting, moving with the arrows, Home and End, pasting and copying with Ctrl, and Enter to
 * submit are all handled here.
 */
public final class TextBox {
    public String text = "";
    public int cursor;
    public String placeholder;
    public int max = 256;
    public Runnable onEnter;
    public Runnable onChange;
    /** Whether the text sits in the middle of the box rather than at its left. */
    public boolean centred;

    public TextBox(String placeholder) {
        this.placeholder = placeholder;
    }

    public void set(String value) {
        text = value == null ? "" : value;
        cursor = text.length();
        if (onChange != null) onChange.run();
    }

    public void type(String s) {
        if (s == null || s.isEmpty()) return;
        s = s.replace("\n", " ").replace("\r", "").replace("§", "");
        int room = max - text.length();
        if (room <= 0) return;
        if (s.length() > room) s = s.substring(0, room);
        text = text.substring(0, cursor) + s + text.substring(cursor);
        cursor += s.length();
        if (onChange != null) onChange.run();
    }

    /** Returns true if the key was used. */
    public boolean key(int key, int modifiers) {
        boolean ctrl = (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
        MinecraftClient mc = MinecraftClient.getInstance();

        switch (key) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (cursor == 0) return true;
                int from = ctrl ? wordStart(cursor) : cursor - 1;
                text = text.substring(0, from) + text.substring(cursor);
                cursor = from;
                if (onChange != null) onChange.run();
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (cursor >= text.length()) return true;
                text = text.substring(0, cursor) + text.substring(cursor + 1);
                if (onChange != null) onChange.run();
            }
            case GLFW.GLFW_KEY_LEFT -> cursor = ctrl ? wordStart(cursor) : Math.max(0, cursor - 1);
            case GLFW.GLFW_KEY_RIGHT -> cursor = Math.min(text.length(), cursor + 1);
            case GLFW.GLFW_KEY_HOME -> cursor = 0;
            case GLFW.GLFW_KEY_END -> cursor = text.length();
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (onEnter != null) onEnter.run();
            }
            case GLFW.GLFW_KEY_V -> {
                if (!ctrl) return false;
                type(mc.keyboard.getClipboard());
            }
            case GLFW.GLFW_KEY_C -> {
                if (!ctrl) return false;
                mc.keyboard.setClipboard(text);
            }
            case GLFW.GLFW_KEY_A -> {
                if (!ctrl) return false;
                cursor = text.length();
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private int wordStart(int from) {
        int i = from;
        while (i > 0 && text.charAt(i - 1) == ' ') i--;
        while (i > 0 && text.charAt(i - 1) != ' ') i--;
        return i;
    }
}
