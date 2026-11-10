package dev.jjstools.panel.panels;

import dev.jjstools.modules.StorageGuiTweaks;
import dev.jjstools.panel.Panel;
import dev.jjstools.panel.Ui;
import dev.jjstools.tint.StorageMemory;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.List;

/**
 * Your ender chest on this server, as it was the last time you opened it, with the same hover
 * tooltips as in game.
 *
 * Read from Storage GUI Tweaks' memory, so it needs that module on and your ender chest opened
 * once on the server.
 */
public class EnderPanel extends Panel {
    public EnderPanel() {
        super("ender", "ender chest", Items.ENDER_CHEST.getDefaultStack(), 176, 70);
    }

    @Override
    public void draw(Ui ui, int x, int y, int w, int h) {
        StorageMemory.Box box = StorageMemory.ender();
        if (box == null) {
            String why = !StorageGuiTweaks.on()
                ? "Turn on Storage GUI Tweaks, then open your ender chest once on this server."
                : "Open your ender chest once on this server.";
            ui.centredWrapped(why, x, w, y + h / 2, ui.fgDim());
            return;
        }

        List<ItemStack> items = box.items();
        int cols = 9;
        int rows = Math.max(1, (items.size() + cols - 1) / cols);
        // The chest grows and shrinks with the panel rather than leaving empty space around it.
        float k = Math.max(0.5F, Math.min((w - 4) / (cols * 18F), (h - 4) / (rows * 18F)));
        int gw = Math.round(cols * 18 * k), gh = Math.round(rows * 18 * k);
        int gx = x + (w - gw) / 2;
        int gy = y + (h - gh) / 2;

        dev.jjstools.compat.Gui.push(ui.ctx);
        dev.jjstools.compat.Gui.translate(ui.ctx, gx, gy);
        dev.jjstools.compat.Gui.scale(ui.ctx, k, k);
        ui.slotGrid(0, 0, cols, rows * cols);
        for (int i = 0; i < items.size(); i++) {
            ItemStack s = items.get(i);
            if (!s.isEmpty()) ui.item(s, (i % cols) * 18 + 1, (i / cols) * 18 + 1);
        }
        for (int i = 0; i < items.size(); i++) {
            ItemStack s = items.get(i);
            if (!s.isEmpty()) {
                StorageGuiTweaks.slotExtras(ui.ctx, s, (i % cols) * 18 + 1, (i / cols) * 18 + 1);
                ui.added(2);
            }
        }
        for (int i = 0; i < items.size(); i++) {
            ItemStack s = items.get(i);
            if (s.getCount() > 1) ui.itemCount(s, (i % cols) * 18 + 1, (i / cols) * 18 + 1, String.valueOf(s.getCount()));
        }
        int hover = -1;
        if (ui.mx >= gx && ui.my >= gy && ui.mx < gx + gw && ui.my < gy + gh) {
            int col = (int) ((ui.mx - gx) / (18 * k));
            int row = (int) ((ui.my - gy) / (18 * k));
            int i = row * cols + col;
            if (col < cols && i < items.size() && !items.get(i).isEmpty()) {
                hover = i;
                ui.fill((i % cols) * 18 + 1, (i / cols) * 18 + 1, 16, 16, 0x80FFFFFF);
            }
        }
        dev.jjstools.compat.Gui.pop(ui.ctx);
        if (hover >= 0) ui.itemTooltip(items.get(hover), Screen.getTooltipFromItem(MinecraftClient.getInstance(), items.get(hover)));
    }
}
