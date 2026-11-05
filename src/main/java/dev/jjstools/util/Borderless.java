package dev.jjstools.util;

import dev.jjstools.JJsTools;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.client.util.Monitor;
import net.minecraft.client.util.VideoMode;
import net.minecraft.client.util.Window;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Borderless windowed mode, offered in the game's own Video Settings next to Fullscreen.
 *
 * The window loses its title bar and border and is stretched over the whole of the monitor it
 * is on, so it looks like fullscreen while still being an ordinary window: alt-tabbing out is
 * instant, nothing flickers, and a second monitor stays usable. Turning it off puts the window
 * back where and how big it was.
 *
 * Real fullscreen still works and wins while it is on; leaving it comes back to borderless if
 * that is switched on. The choice is kept in .minecraft/jjs-tools/borderless.txt, outside the
 * game's options file, so the game never sees a setting it does not know.
 */
public final class Borderless {
    private static final Path FILE = FabricLoader.getInstance().getGameDir().resolve("jjs-tools").resolve("borderless.txt");

    public static final SimpleOption<Boolean> OPTION = SimpleOption.ofBoolean(
        "options.jjs-tools.borderless",
        SimpleOption.constantTooltip(Text.literal("Fills the screen like fullscreen but stays a normal window with no border, so switching to other windows is instant. Added by JJ's Tools.")),
        read(),
        Borderless::changed
    );

    /** Where the window was before it went borderless, to put it back there. */
    private static int savedX, savedY, savedWidth, savedHeight;
    private static boolean saved;
    private static boolean applied;

    private Borderless() {}

    /** Once the window exists at startup: go borderless straight away if it was left on. */
    public static void onStartup() {
        if (OPTION.getValue()) apply(true);
    }

    /** After the game goes in or out of real fullscreen: back to borderless if it is on. */
    public static void afterFullscreenToggle(Window window) {
        // Going into fullscreen: nothing to do, and the remembered window is kept for later.
        if (!OPTION.getValue() || window.isFullscreen()) return;
        apply(true);
    }

    private static void changed(boolean on) {
        write(on);
        apply(on);
    }

    private static void apply(boolean on) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getWindow() == null) return;

        Window window = mc.getWindow();
        long handle = window.getHandle();

        if (on) {
            // Real fullscreen first has to go, or the window is still owned by the monitor.
            if (window.isFullscreen()) window.toggleFullscreen();

            if (!applied) {
                savedX = window.getX();
                savedY = window.getY();
                savedWidth = window.getWidth();
                savedHeight = window.getHeight();
                saved = true;
            }

            int x, y, width, height;
            Monitor monitor = window.getMonitor();
            if (monitor != null) {
                VideoMode mode = monitor.getCurrentVideoMode();
                x = monitor.getViewportX();
                y = monitor.getViewportY();
                width = mode.getWidth();
                height = mode.getHeight();
            }
            else {
                long primary = GLFW.glfwGetPrimaryMonitor();
                GLFWVidMode mode = primary == 0 ? null : GLFW.glfwGetVideoMode(primary);
                if (mode == null) return;
                int[] mx = new int[1];
                int[] my = new int[1];
                GLFW.glfwGetMonitorPos(primary, mx, my);
                x = mx[0];
                y = my[0];
                width = mode.width();
                height = mode.height();
            }

            GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
            GLFW.glfwSetWindowMonitor(handle, 0L, x, y, width, height, GLFW.GLFW_DONT_CARE);
            applied = true;
        }
        else {
            if (!applied) return;
            GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);

            if (saved && savedWidth > 0 && savedHeight > 0) {
                GLFW.glfwSetWindowMonitor(handle, 0L, savedX, savedY, savedWidth, savedHeight, GLFW.GLFW_DONT_CARE);
            }
            applied = false;
        }
    }

    private static boolean read() {
        try {
            return Files.exists(FILE) && Files.readString(FILE).trim().equalsIgnoreCase("true");
        } catch (Exception e) {
            return false;
        }
    }

    private static void write(boolean on) {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, Boolean.toString(on));
        } catch (Exception e) {
            JJsTools.LOG.warn("Could not save the borderless windowed setting", e);
        }
    }
}
