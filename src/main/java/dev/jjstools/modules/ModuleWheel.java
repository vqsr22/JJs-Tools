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
        .description("The modules on the wheel. Right-click and drag one in the wheel to move it.")
        .build()
    );

    private final Setting<List<String>> commands = sgGeneral.add(new StringListSetting.Builder()
        .name("commands")
        .description("Commands on the wheel, run when chosen: /home for the server, .toggle for Meteor, #goto for Baritone. Each one's name and icon are set below.")
        .onChanged(list -> refreshIfCountChanged())
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

    private final Setting<Double> iconSize = sgGeneral.add(new DoubleSetting.Builder()
        .name("icon-size")
        .description("How big the icons are on the wheel.")
        .defaultValue(1.0)
        .range(0.5, 3.0)
        .sliderRange(0.5, 3.0)
        .build()
    );

    private final Setting<Double> textSize = sgGeneral.add(new DoubleSetting.Builder()
        .name("text-size")
        .description("How big the names are on the wheel.")
        .defaultValue(1.0)
        .range(0.5, 3.0)
        .sliderRange(0.5, 3.0)
        .build()
    );

    private final Setting<Boolean> hint = sgGeneral.add(new BoolSetting.Builder()
        .name("show-hint-message")
        .description("Show how to move modules under the wheel.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> animationTime = sgGeneral.add(new IntSetting.Builder()
        .name("animation-time")
        .description("How long the wheel takes to open, in milliseconds. 0 turns the animation off.")
        .defaultValue(90)
        .min(0)
        .sliderRange(0, 500)
        .build()
    );

    /** How far an animation that began at startMs is, 0 to 1, over the wheel's animation time; 1 straight away at 0. */
    private double progress(double startMs, double scale) {
        double ms = animationTime.get() * scale;
        if (ms <= 0) return 1;
        return dev.jjstools.util.Motion.clamp01((dev.jjstools.util.Motion.now() - startMs) / ms);
    }

    private boolean animated() {
        return animationTime.get() > 0;
    }

    private int commandLines = -1;

    /**
     * Adding or removing a command line opens the settings again straight away, so its name and
     * icon show below at once. Only then, not while a command is typed, so typing is not cut off.
     */
    private void refreshIfCountChanged() {
        int count = commands.get().size();
        if (commandLines >= 0 && count != commandLines
            && mc.currentScreen instanceof meteordevelopment.meteorclient.gui.WidgetScreen screen) {
            mc.send(screen::reload);
        }
        commandLines = count;
    }

    /** The key was down last tick, so holding it after the wheel closes does not open it again. */
    private boolean wasDown;

    /** Each slot's icon, by its key (a module's name, or "cmd:" and the command): the item, and whether it shimmers. */
    private record Icon(net.minecraft.item.Item item, boolean glint) {}
    private final java.util.Map<String, Icon> icons = new java.util.HashMap<>();
    /** The names given to commands on the wheel, by key; a command without one shows itself. */
    private final java.util.Map<String, String> names = new java.util.HashMap<>();
    /** The order of the slots round the wheel by key, as last arranged; anything new goes at the end. */
    private final List<String> order = new ArrayList<>();

    /** One place on the wheel: a module to turn on or off, or a command to run. */
    private record Slot(String key, String label, Module module, String command) {
        boolean active() {
            return module != null && module.isActive();
        }
    }

    private List<Slot> slots() {
        java.util.Map<String, Slot> byKey = new java.util.LinkedHashMap<>();
        for (Module m : modules.get()) byKey.put(m.name, new Slot(m.name, m.title.toLowerCase(Locale.ROOT), m, null));
        List<String> lines = commands.get();
        for (int i = 0; i < lines.size(); i++) {
            String command = lines.get(i).trim();
            if (command.isEmpty()) continue;
            // Known by its line, so its name and icon stay with it while the command is edited.
            String key = "cmd:" + i;
            String name = names.getOrDefault(key, "").trim();
            byKey.put(key, new Slot(key, name.isEmpty() ? command : name, null, command));
        }
        List<Slot> out = new ArrayList<>();
        for (String key : order) {
            Slot slot = byKey.remove(key);
            if (slot != null) out.add(slot);
        }
        out.addAll(byKey.values());
        return out;
    }

    private Icon iconFor(Slot slot) {
        Icon icon = icons.get(slot.key());
        if (icon != null) return icon;
        // Until one is picked: the module's category icon, or a command block for a command.
        if (slot.module() != null) return new Icon(dev.jjstools.util.CategoryIcons.itemFor(slot.module().category), false);
        return new Icon(net.minecraft.item.Items.COMMAND_BLOCK, false);
    }

    private net.minecraft.item.ItemStack stackFor(Slot slot) {
        Icon icon = iconFor(slot);
        return dev.jjstools.util.CategoryIcons.stackFor(icon.item(), icon.glint());
    }

    /** For every slot on the wheel, under the settings: its icon and an enchanted switch, and a name for commands. */
    @Override
    public meteordevelopment.meteorclient.gui.widgets.WWidget getWidget(meteordevelopment.meteorclient.gui.GuiTheme theme) {
        commandLines = commands.get().size();
        Settings wheel = new Settings();
        // Every module on the wheel, then every command line, even one not filled in yet, so a
        // new line's name and icon are there to set straight away.
        List<Slot> all = new ArrayList<>();
        for (Slot slot : slots()) if (slot.module() != null) all.add(slot);
        List<String> lines = commands.get();
        for (int i = 0; i < lines.size(); i++) all.add(new Slot("cmd:" + i, lines.get(i).trim(), null, lines.get(i).trim()));
        for (Slot slot : all) {
            String title = slot.module() != null ? slot.label()
                : "command " + (Integer.parseInt(slot.key().substring(4)) + 1) + (slot.command().isEmpty() ? "" : ": " + slot.command());
            SettingGroup group = wheel.createGroup(title);
            Icon now = iconFor(slot);
            if (slot.command() != null) {
                group.add(new StringSetting.Builder()
                    .name("name")
                    .description("What the wheel shows for " + slot.command() + ". Empty shows the command itself.")
                    .defaultValue(names.getOrDefault(slot.key(), ""))
                    .onChanged(name -> {
                        if (name.isBlank()) names.remove(slot.key());
                        else names.put(slot.key(), name.trim());
                    })
                    .build());
            }
            group.add(new ItemSetting.Builder()
                .name("icon")
                .description("The item shown for " + slot.label() + " on the wheel.")
                .defaultValue(now.item())
                .onChanged(item -> icons.put(slot.key(), new Icon(item, iconFor(slot).glint())))
                .build());
            group.add(new BoolSetting.Builder()
                .name("enchanted")
                .description("Give the icon an enchantment shimmer.")
                .defaultValue(now.glint())
                .onChanged(glint -> icons.put(slot.key(), new Icon(iconFor(slot).item(), glint)))
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
        net.minecraft.nbt.NbtCompound savedNames = new net.minecraft.nbt.NbtCompound();
        for (var e : names.entrySet()) savedNames.putString(e.getKey(), e.getValue());
        tag.put("wheel-names", savedNames);
        tag.putString("wheel-order", String.join("\n", order));
        return tag;
    }

    @Override
    public Module fromTag(net.minecraft.nbt.NbtCompound tag) {
        super.fromTag(tag);
        icons.clear();
        net.minecraft.nbt.NbtCompound saved = tag.getCompoundOrEmpty("wheel-icons");
        for (String key : saved.getKeys()) {
            String value = saved.getString(key, "");
            int bar = value.lastIndexOf('|');
            String itemId = bar >= 0 ? value.substring(0, bar) : value;
            boolean glint = bar >= 0 && Boolean.parseBoolean(value.substring(bar + 1));
            var id = net.minecraft.util.Identifier.tryParse(itemId);
            if (id == null || !net.minecraft.registry.Registries.ITEM.containsId(id)) continue;
            icons.put(key, new Icon(net.minecraft.registry.Registries.ITEM.get(id), glint));
        }
        names.clear();
        net.minecraft.nbt.NbtCompound savedNames = tag.getCompoundOrEmpty("wheel-names");
        for (String key : savedNames.getKeys()) names.put(key, savedNames.getString(key, ""));
        order.clear();
        String savedOrder = tag.getString("wheel-order", "");
        if (!savedOrder.isEmpty()) order.addAll(List.of(savedOrder.split("\n")));
        return this;
    }

    public ModuleWheel() {
        super(JJsTools.CATEGORY, "module-wheel", "Hold a key to open a wheel of modules and commands and let go over one to turn it on or off or run it. Right-click and drag one in the wheel to move it to another slot.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        checkKey();
    }

    /** Checked every frame as well as every tick, so the wheel opens the moment the key goes down rather than on the next tick. */
    @EventHandler
    private void onRender2D(meteordevelopment.meteorclient.events.render.Render2DEvent event) {
        checkKey();
    }

    private void checkKey() {
        boolean down = wheelKey.get().isSet() && wheelKey.get().isPressed();
        if (down && !wasDown && mc.currentScreen == null && !slots().isEmpty()) {
            mc.setScreen(new WheelScreen());
        }
        wasDown = down;
    }

    private final class WheelScreen extends Screen {
        private final List<Slot> list = slots();
        private int[] palette;
        private boolean done;
        /** The slot being moved with the right mouse button, or null. */
        private Slot dragging;
        /** Where each slot is drawn right now: its angle round the wheel and distance out. */
        private final java.util.Map<Slot, double[]> shown = new java.util.HashMap<>();
        private final double openedAt = dev.jjstools.util.Motion.now();
        private double closingAt = -1;
        /** The slot chosen as the wheel closes, which pulses to show it took. */
        private Slot pulsed;

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
            checkRelease();
        }

        /**
         * Let go of the key: choose whatever is under the mouse and close. Mid-move it just
         * closes, keeping the new order. Checked every frame, so there is no wait for a tick.
         */
        private void checkRelease() {
            if (done) return;
            if (!wheelKey.get().isPressed()) {
                if (dragging != null) {
                    finish(null);
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
            if (done) return true;
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
                // Kept for next time, and saved with the module.
                order.clear();
                for (Slot slot : list) order.add(slot.key());
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
            Slot slot = index >= 0 && index < list.size() ? list.get(index) : null;
            finish(slot);
            if (slot == null) return;
            if (slot.module() != null) slot.module().toggle();
            else if (mc.player != null) meteordevelopment.meteorclient.utils.player.ChatUtils.sendPlayerMsg(slot.command());
        }

        /** Starts the wheel shrinking away, the chosen slot giving a pulse; it closes when that is done. */
        private void finish(Slot chosen) {
            if (done) return;
            done = true;
            dragging = null;
            closingAt = dev.jjstools.util.Motion.now();
            if (chosen != null) {
                pulsed = chosen;
                if (animated()) dev.jjstools.util.Motion.pulse(chosen);
            }
        }

        @Override
        public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
            super.render(ctx, mouseX, mouseY, delta);
            checkRelease();
            // Shrinking away after a choice: closed once that is over. With no animation time
            // it closes at once.
            double whole;
            if (closingAt >= 0) {
                whole = 1 - dev.jjstools.util.Motion.easeIn(progress(closingAt, 0.8));
                if (whole <= 0) {
                    close();
                    return;
                }
            } else {
                whole = dev.jjstools.util.Motion.easeOut(progress(openedAt, 1));
            }
            if (palette == null) palette = Ui.palette(Ui.currentTheme());
            int panel = palette[0], well = palette[1], accent = palette[3], text = palette[4];
            TextRenderer tr = textRenderer;
            boolean font = UiFont.ready();

            int cx = width / 2, cy = height / 2;
            int r = size.get();
            int safe = (int) Math.round(r * safeSize.get());
            // The whole wheel grows out of its middle from 95% as it opens, easing out, and
            // shrinks back into it, a fifth quicker, when done.
            float grow = (float) (dev.jjstools.util.Motion.POPOVER_START + (1 - dev.jjstools.util.Motion.POPOVER_START) * whole);
            var whole2 = ctx.getMatrices();
            whole2.pushMatrix();
            whole2.translate(cx, cy);
            whole2.scale(grow, grow);
            whole2.translate(-cx, -cy);
            disc(ctx, cx, cy, r, panel);
            disc(ctx, cx, cy, safe, well);

            int over = closingAt >= 0 ? (pulsed != null ? list.indexOf(pulsed) : -1)
                : dragging != null ? list.indexOf(dragging) : hovered(mouseX, mouseY);
            double step = 360.0 / list.size();
            double labelR = (safe + r) / 2.0;

            // Each slot moves round the wheel on an arc, the shorter way, easing in and out, rather
            // than jumping. On opening they all sweep out from the middle together, with no wait. The one
            // being moved sits exactly under the cursor while held, with no easing to lag it.
            double now = dev.jjstools.util.Motion.now();
            for (int i = 0; i < list.size(); i++) {
                Slot m = list.get(i);
                double slot = i * step - 90;
                // angle, distance, from angle, from distance, start time, target angle, delay, coming in
                double[] p = shown.computeIfAbsent(m, x -> new double[] { slot - 15, safe, slot - 15, safe, openedAt, slot, 0, 1 });
                if (m == dragging) {
                    p[0] = Math.toDegrees(Math.atan2(mouseY - cy, mouseX - cx));
                    p[1] = Math.hypot(mouseX - cx, mouseY - cy);
                    p[5] = Double.NaN;   // starts a move from here when let go
                    continue;
                }
                if (p[5] != slot) {
                    p[2] = p[0];
                    p[3] = p[1];
                    p[4] = now;
                    p[5] = slot;
                    p[6] = 0;
                    p[7] = 0;
                }
                double e = p[7] == 1 ? dev.jjstools.util.Motion.easeOut(progress(p[4], 1)) : dev.jjstools.util.Motion.easeInOut(progress(p[4], 1));
                double diff = ((slot - p[2]) % 360 + 540) % 360 - 180;
                p[0] = p[2] + diff * e;
                p[1] = p[3] + (labelR - p[3]) * e;
            }

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
            whole2.popMatrix();
        }

        private void slot(DrawContext ctx, TextRenderer tr, boolean font, Slot m, boolean hot, int cx, int cy, int panel, int accent, int text) {
            double[] p = shown.get(m);
            if (p == null) return;
            int lineH = 9;
            String name = m.label();
            double a = Math.toRadians(p[0]);
            double lx = cx + Math.cos(a) * p[1];
            double ly = cy + Math.sin(a) * p[1];
            // The icon above the name, the two together centred on the slot, each at its own size.
            float ki = iconSize.get().floatValue(), kt = textSize.get().floatValue();
            float w = (font ? UiFont.width(tr, name) : tr.getWidth(name)) * kt;
            float icon = 16 * ki, textH = lineH * kt;
            int pw = Math.round(Math.max(w, icon) + 10), ph = Math.round(4 + icon + 2 + textH + 4);
            float k = (float) dev.jjstools.util.Motion.pulseScale(m);
            var matrices = ctx.getMatrices();
            matrices.pushMatrix();
            matrices.translate((float) lx, (float) ly);
            matrices.scale(k, k);
            int px = -pw / 2, py = -ph / 2;
            // Monkeytype's swap: under the mouse, text colour behind and background colour on top.
            if (hot) round(ctx, px, py, pw, ph, 5, text, k);
            matrices.pushMatrix();
            matrices.translate(0, py + 4);
            matrices.scale(ki, ki);
            ctx.drawItem(stackFor(m), -8, 0);
            matrices.popMatrix();
            int ink = hot ? panel : m.active() ? accent : text;
            matrices.pushMatrix();
            matrices.translate(-w / 2, py + 4 + icon + 2);
            matrices.scale(kt, kt);
            if (font) UiFont.draw(ctx, tr, name, 0, 0, 1.0F, ink);
            else ctx.drawText(tr, name, 0, 0, ink, false);
            matrices.popMatrix();
            matrices.popMatrix();
        }

        private void disc(DrawContext ctx, int cx, int cy, int r, int argb) {
            int rp = Math.max(1, (int) Math.round(r * MinecraftClient.getInstance().getWindow().getScaleFactor()));
            var c = UiShapes.circle(rp);
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, c.id(), cx - r, cy - r, 0, 0, r * 2, r * 2, rp * 2, rp * 2, c.w(), c.h(), argb);
        }

        private void round(DrawContext ctx, int x, int y, int w, int h, int r, int argb, float scale) {
            r = Math.min(r, Math.min(w, h) / 2);
            ctx.fill(x + r, y, x + w - r, y + h, argb);
            ctx.fill(x, y + r, x + r, y + h - r, argb);
            ctx.fill(x + w - r, y + r, x + w, y + h - r, argb);
            int rp = Math.max(1, (int) Math.round(r * scale * MinecraftClient.getInstance().getWindow().getScaleFactor()));
            var c = UiShapes.circle(rp);
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, c.id(), x, y, 0, 0, r, r, rp, rp, c.w(), c.h(), argb);
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, c.id(), x + w - r, y, rp, 0, r, r, rp, rp, c.w(), c.h(), argb);
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, c.id(), x, y + h - r, 0, rp, r, r, rp, rp, c.w(), c.h(), argb);
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, c.id(), x + w - r, y + h - r, rp, rp, r, r, rp, rp, c.w(), c.h(), argb);
        }
    }
}
