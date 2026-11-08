package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import dev.jjstools.panel.Ui;
import dev.jjstools.panel.UiFont;
import dev.jjstools.panel.UiShapes;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A wheel of modules on a key: hold the key, move the mouse onto a module and let go to turn it
 * on or off. Letting go in the circle in the middle does nothing, so the wheel can be opened and
 * closed without changing anything. Right-click and drag a module to move it to another slot.
 * Each module has an icon, any item, enchanted or not, set under the module's settings. Drawn
 * in the Control Panel's theme colours and font.
 */
public class ModuleWheel extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Keybind> wheelKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("wheel-key")
        .description("Hold to open the wheel; let go over a module to turn it on or off.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<List<Module>> modules = sgGeneral.add(new ModuleListSetting.Builder()
        .name("modules")
        .description("The modules on the wheel, clockwise from the top. Right-click and drag one in the wheel to move it.")
        .build()
    );

    private final Setting<Integer> size = sgGeneral.add(new IntSetting.Builder()
        .name("size")
        .description("How far the wheel reaches from its middle, in GUI pixels.")
        .defaultValue(90)
        .min(40)
        .sliderRange(50, 160)
        .build()
    );

    private final Setting<Double> safeSize = sgGeneral.add(new DoubleSetting.Builder()
        .name("safe-circle")
        .description("How big the circle in the middle is, as a share of the wheel. Letting go inside it changes nothing.")
        .defaultValue(0.35)
        .range(0.15, 0.7)
        .sliderRange(0.15, 0.7)
        .build()
    );

    private final Setting<Boolean> hint = sgGeneral.add(new BoolSetting.Builder()
        .name("show-hint-message")
        .description("Show how to move modules under the wheel.")
        .defaultValue(true)
        .build()
    );

    /** The key was down last tick, so holding it after the wheel closes does not open it again. */
    private boolean wasDown;

    /** Each module's icon on the wheel, by module name: the item, and whether it shimmers. */
    private record Icon(net.minecraft.item.Item item, boolean glint) {}
    private final java.util.Map<String, Icon> icons = new java.util.HashMap<>();

    private Icon iconFor(Module m) {
        Icon icon = icons.get(m.name);
        if (icon != null) return icon;
        // Until one is picked, the icon of the module's category.
        return new Icon(dev.jjstools.util.CategoryIcons.itemFor(m.category), false);
    }

    private net.minecraft.item.ItemStack stackFor(Module m) {
        Icon icon = iconFor(m);
        return dev.jjstools.util.CategoryIcons.stackFor(icon.item(), icon.glint());
    }

    /** An icon and an enchanted switch for every module on the wheel, under the settings. */
    @Override
    public meteordevelopment.meteorclient.gui.widgets.WWidget getWidget(meteordevelopment.meteorclient.gui.GuiTheme theme) {
        Settings wheel = new Settings();
        for (Module m : modules.get()) {
            SettingGroup group = wheel.createGroup(m.title);
            Icon now = iconFor(m);
            group.add(new ItemSetting.Builder()
                .name("icon")
                .description("The item shown for " + m.title + " on the wheel.")
                .defaultValue(now.item())
                .onChanged(item -> { icons.put(m.name, new Icon(item, iconFor(m).glint())); })
                .build());
            group.add(new BoolSetting.Builder()
                .name("enchanted")
                .description("Give the icon an enchantment shimmer.")
                .defaultValue(now.glint())
                .onChanged(glint -> { icons.put(m.name, new Icon(iconFor(m).item(), glint)); })
                .build());
        }
        return theme.settings(wheel);
    }

    @Override
    public net.minecraft.nbt.NbtCompound toTag() {
        net.minecraft.nbt.NbtCompound tag = super.toTag();
        if (tag == null) return null;
        net.minecraft.nbt.NbtCompound saved = new net.minecraft.nbt.NbtCompound();
        for (var e : icons.entrySet()) {
            saved.putString(e.getKey(), net.minecraft.registry.Registries.ITEM.getId(e.getValue().item()) + "|" + e.getValue().glint());
        }
        tag.put("wheel-icons", saved);
        return tag;
    }

    @Override
    public Module fromTag(net.minecraft.nbt.NbtCompound tag) {
        super.fromTag(tag);
        icons.clear();
        net.minecraft.nbt.NbtCompound saved = tag.getCompoundOrEmpty("wheel-icons");
        for (String key : saved.getKeys()) {
            String[] parts = saved.getString(key, "").split("\\|");
            var id = net.minecraft.util.Identifier.tryParse(parts[0]);
            if (id == null || !net.minecraft.registry.Registries.ITEM.containsId(id)) continue;
            icons.put(key, new Icon(net.minecraft.registry.Registries.ITEM.get(id), parts.length > 1 && Boolean.parseBoolean(parts[1])));
        }
        return this;
    }

    public ModuleWheel() {
        super(JJsTools.CATEGORY, "module-wheel", "Hold a key to open a wheel of modules and let go over one to turn it on or off. Right-click and drag a module in the wheel to move it to another slot.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        boolean down = wheelKey.get().isSet() && wheelKey.get().isPressed();
        if (down && !wasDown && mc.currentScreen == null && !modules.get().isEmpty()) {
            mc.setScreen(new WheelScreen());
        }
        wasDown = down;
    }

    private final class WheelScreen extends Screen {
        private final List<Module> list = new ArrayList<>(modules.get());
        private int[] palette;
        private boolean done;
        /** The module being moved with the right mouse button, or null. */
        private Module dragging;
        /** Where each module is drawn right now: its angle round the wheel and distance out. */
        private final java.util.Map<Module, double[]> shown = new java.util.HashMap<>();
        private long lastFrame;
        private boolean first = true;

        WheelScreen() {
            super(Text.literal("module wheel"));
        }

        @Override
        public boolean shouldPause() {
            return false;
        }

        @Override
        public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
            // A light dim behind the wheel and no blur, so the game stays in view.
            context.fill(0, 0, width, height, 0x40000000);
        }

        /** The module under the mouse, or -1 in the middle circle. */
        private int hovered(double mouseX, double mouseY) {
            double dx = mouseX - width / 2.0, dy = mouseY - height / 2.0;
            if (Math.hypot(dx, dy) < size.get() * safeSize.get()) return -1;
            double angle = Math.toDegrees(Math.atan2(dy, dx)) + 90;   // 0 at the top, clockwise
            double step = 360.0 / list.size();
            angle = ((angle + step / 2) % 360 + 360) % 360;
            return Math.min(list.size() - 1, (int) (angle / step));
        }

        @Override
        public void tick() {
            // Let go of the key: choose whatever is under the mouse and close. Mid-move it just
            // closes, keeping the new order.
            if (!wheelKey.get().isPressed()) {
                if (dragging != null) {
                    dragging = null;
                    done = true;
                    close();
                } else {
                    choose(hoveredNow());
                }
            }
        }

        /**
         * Right-click a module and drag: it snaps from slot to slot under the mouse, the others
         * moving round to make room, and stays where it is let go. The middle circle leaves it
         * where it started.
         */
        @Override
        public boolean mouseClicked(Click click, boolean doubled) {
            if (click.button() == 1) {
                int at = hovered(click.x(), click.y());
                if (at >= 0) dragging = list.get(at);
                return true;
            }
            if (click.button() == 0 && dragging == null) {
                choose(hovered(click.x(), click.y()));
                return true;
            }
            return super.mouseClicked(click, doubled);
        }

        @Override
        public boolean mouseDragged(Click click, double offsetX, double offsetY) {
            if (dragging != null) {
                int to = hovered(click.x(), click.y());
                int from = list.indexOf(dragging);
                if (to >= 0 && to != from) {
                    list.remove(from);
                    list.add(to, dragging);
                }
                return true;
            }
            return super.mouseDragged(click, offsetX, offsetY);
        }

        @Override
        public boolean mouseReleased(Click click) {
            if (click.button() == 1 && dragging != null) {
                dragging = null;
                // The wheel's order is the modules setting's order, so it is kept.
                modules.set(new ArrayList<>(list));
                return true;
            }
            return super.mouseReleased(click);
        }

        private int hoveredNow() {
            MinecraftClient c = MinecraftClient.getInstance();
            return hovered(c.mouse.getScaledX(c.getWindow()), c.mouse.getScaledY(c.getWindow()));
        }

        private void choose(int index) {
            if (done) return;
            done = true;
            if (index >= 0 && index < list.size()) list.get(index).toggle();
            close();
        }

        @Override
        public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
            super.render(ctx, mouseX, mouseY, delta);
            if (palette == null) palette = Ui.palette(Ui.currentTheme());
            int panel = palette[0], well = palette[1], accent = palette[3], text = palette[4];
            TextRenderer tr = textRenderer;
            boolean font = UiFont.ready();

            int cx = width / 2, cy = height / 2;
            int r = size.get();
            int safe = (int) Math.round(r * safeSize.get());
            disc(ctx, cx, cy, r, panel);
            disc(ctx, cx, cy, safe, well);

            int over = dragging != null ? list.indexOf(dragging) : hovered(mouseX, mouseY);
            double step = 360.0 / list.size();
            double labelR = (safe + r) / 2.0;

            // Each module glides to its slot round the wheel rather than jumping, along the
            // shorter way round; the one being moved sits under the cursor until let go.
            long now = System.nanoTime();
            double seconds = lastFrame == 0 ? 0 : Math.min(0.1, (now - lastFrame) / 1e9);
            lastFrame = now;
            double k = 1 - Math.exp(-seconds * 14);
            for (int i = 0; i < list.size(); i++) {
                Module m = list.get(i);
                double[] p = shown.computeIfAbsent(m, x -> new double[] { 0, labelR });
                double slot = i * step - 90;
                if (m == dragging) {
                    p[0] = Math.toDegrees(Math.atan2(mouseY - cy, mouseX - cx));
                    p[1] = Math.hypot(mouseX - cx, mouseY - cy);
                } else if (first) {
                    p[0] = slot;
                    p[1] = labelR;
                } else {
                    double diff = ((slot - p[0]) % 360 + 540) % 360 - 180;
                    p[0] += diff * k;
                    p[1] += (labelR - p[1]) * k;
                }
            }
            first = false;

            if (hint.get()) {
                int dim = palette[5];
                String one = "right click and drag a module to move it";
                String two = "hide this message in module wheel";
                int y = cy + r + 8;
                for (String line : new String[] { one, two }) {
                    int w = Math.round(font ? UiFont.width(tr, line) : tr.getWidth(line));
                    if (font) UiFont.draw(ctx, tr, line, cx - w / 2, y, 1.0F, dim);
                    else ctx.drawText(tr, line, cx - w / 2, y, dim, false);
                    y += 11;
                }
            }

            // The one being moved last, so it is drawn over the rest.
            for (int i = 0; i < list.size(); i++) if (list.get(i) != dragging) slot(ctx, tr, font, list.get(i), i == over, cx, cy, panel, accent, text);
            if (dragging != null) slot(ctx, tr, font, dragging, true, cx, cy, panel, accent, text);
        }

        private void slot(DrawContext ctx, TextRenderer tr, boolean font, Module m, boolean hot, int cx, int cy, int panel, int accent, int text) {
            double[] p = shown.get(m);
            if (p == null) return;
            int lineH = 9;
            String name = m.title.toLowerCase(Locale.ROOT);
            double a = Math.toRadians(p[0]);
            int lx = (int) Math.round(cx + Math.cos(a) * p[1]);
            int ly = (int) Math.round(cy + Math.sin(a) * p[1]);
            int w = Math.round(font ? UiFont.width(tr, name) : tr.getWidth(name));
            // The icon above the name, the two together centred on the slot.
            int pw = Math.max(w, 16) + 10, ph = 16 + 2 + lineH + 8;
            int px = lx - pw / 2, py = ly - ph / 2;
            // Monkeytype's swap: under the mouse, text colour behind and background colour on top.
            if (hot) round(ctx, px, py, pw, ph, 5, text);
            ctx.drawItem(stackFor(m), lx - 8, py + 4);
            int ink = hot ? panel : m.isActive() ? accent : text;
            int ty = py + 4 + 16 + 2;
            if (font) UiFont.draw(ctx, tr, name, lx - w / 2, ty, 1.0F, ink);
            else ctx.drawText(tr, name, lx - w / 2, ty, ink, false);
        }

        private void disc(DrawContext ctx, int cx, int cy, int r, int argb) {
            int rp = Math.max(1, (int) Math.round(r * MinecraftClient.getInstance().getWindow().getScaleFactor()));
            var c = UiShapes.circle(rp);
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, c.id(), cx - r, cy - r, 0, 0, r * 2, r * 2, rp * 2, rp * 2, c.w(), c.h(), argb);
        }

        private void round(DrawContext ctx, int x, int y, int w, int h, int r, int argb) {
            r = Math.min(r, Math.min(w, h) / 2);
            ctx.fill(x + r, y, x + w - r, y + h, argb);
            ctx.fill(x, y + r, x + r, y + h - r, argb);
            ctx.fill(x + w - r, y + r, x + w, y + h - r, argb);
            int rp = Math.max(1, (int) Math.round(r * MinecraftClient.getInstance().getWindow().getScaleFactor()));
            var c = UiShapes.circle(rp);
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, c.id(), x, y, 0, 0, r, r, rp, rp, c.w(), c.h(), argb);
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, c.id(), x + w - r, y, rp, 0, r, r, rp, rp, c.w(), c.h(), argb);
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, c.id(), x, y + h - r, 0, rp, r, r, rp, rp, c.w(), c.h(), argb);
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, c.id(), x + w - r, y + h - r, rp, rp, r, r, rp, rp, c.w(), c.h(), argb);
        }
    }
}
