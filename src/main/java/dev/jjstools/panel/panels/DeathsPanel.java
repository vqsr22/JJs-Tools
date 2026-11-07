package dev.jjstools.panel.panels;

import dev.jjstools.panel.DeathLog;
import dev.jjstools.panel.Panel;
import dev.jjstools.panel.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Everywhere you have died, newest first: when, where, and what killed you, by player, mob,
 * fall, fire, suffocation and the rest, with the death message itself. Copy a death's
 * coordinates to the clipboard to go back for your things.
 */
public class DeathsPanel extends Panel {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm").withZone(ZoneId.systemDefault());

    private final double[] scroll = { 0 };
    private boolean confirmClear;

    public DeathsPanel() {
        super("deaths", "death log", Items.SKELETON_SKULL.getDefaultStack(), 170, 120);
    }

    @Override
    public String badge() {
        return String.valueOf(DeathLog.all().size());
    }

    @Override
    public void draw(Ui ui, int x, int y, int w, int h) {
        List<DeathLog.Death> deaths = DeathLog.all();
        int bh = Math.max(16, ui.lineH() + 7);

        int cw = Math.max(ui.width("sure?"), ui.width("clear all")) + 24;
        ui.button(x + w - cw, y, cw, bh, confirmClear ? "sure?" : "clear all", !deaths.isEmpty(), () -> {
            if (confirmClear) {
                DeathLog.clear();
                confirmClear = false;
            } else confirmClear = true;
        });
        if (!deaths.isEmpty()) {
            DeathLog.Death last = deaths.get(0);
            ui.text(ui.fit("last: " + last.cause.title.toLowerCase(java.util.Locale.ROOT) + ", " + ChatPanel.ago(last.time) + " ago", w - cw - 6), x, y + (bh - ui.lineH()) / 2 + 1, ui.fgDim());
        }

        int ly = y + bh + 4;
        int lh = h - (ly - y);
        int lineStep = ui.lineH() + 2;
        int card = lineStep * 3 + 10;
        ui.well(x, ly, w, lh);
        ui.clip(x + 1, ly + 1, w - 2, lh - 2);
        int off = ui.scrollArea(x + 1, ly + 1, w - 2, lh - 2, deaths.size() * card + 2, scroll, card);
        int cy = ly + 1 - off;

        if (deaths.isEmpty()) ui.centredWrapped("no deaths", x, w, ly + lh / 2, ui.fgWellDim());

        for (DeathLog.Death d : deaths) {
            if (cy + card < ly || cy > ly + lh) {
                cy += card;
                continue;
            }
            if (ui.hovered(x + 1, cy, w - 8, card)) ui.fill(x + 1, cy, w - 8, card, ui.rowHover());
            ui.item(icon(d.cause), x + 4, cy + 4);

            int tx = x + 24;
            String cause = d.cause.title.toLowerCase(java.util.Locale.ROOT) + (d.killer != null && !d.killer.isBlank() ? ": " + d.killer : "");
            ui.text(ui.fit(cause, w - 40), tx, cy + 4, colour(ui, d.cause));
            boolean hide = dev.jjstools.panel.Prefs.get().hideCoords;
            String where = (hide ? "" : d.x + ", " + d.y + ", " + d.z + "  ") + dimension(d.dimension);
            ui.text(ui.fit(where, w - 40), tx, cy + 4 + lineStep, ui.fgWell());
            String detail = WHEN.format(Instant.ofEpochMilli(d.time)) + "  -  " + (d.message == null ? "" : d.message);
            ui.text(ui.fit(detail, w - 40), tx, cy + 4 + lineStep * 2, ui.fgWellDim());

                        if (!hide) ui.onClick(x + 1, cy, w - 8, card, 0, b -> MinecraftClient.getInstance().keyboard.setClipboard(d.x + " " + d.y + " " + d.z));
            ui.onClick(x + 1, cy, w - 8, card, 1, b -> DeathLog.remove(d));
            cy += card;
        }
        ui.unclip();
    }

    private static ItemStack icon(DeathLog.Cause cause) {
        return switch (cause) {
            case Player -> Items.NETHERITE_SWORD.getDefaultStack();
            case Mob -> Items.ZOMBIE_HEAD.getDefaultStack();
            case Fall -> Items.FEATHER.getDefaultStack();
            case Fire -> Items.FLINT_AND_STEEL.getDefaultStack();
            case Lava -> Items.LAVA_BUCKET.getDefaultStack();
            case Suffocation -> Items.GRAVEL.getDefaultStack();
            case Drowning -> Items.WATER_BUCKET.getDefaultStack();
            case Explosion -> Items.TNT.getDefaultStack();
            case Void -> Items.ENDER_EYE.getDefaultStack();
            case Starvation -> Items.ROTTEN_FLESH.getDefaultStack();
            case Wither -> Items.WITHER_ROSE.getDefaultStack();
            case Magic -> Items.SPLASH_POTION.getDefaultStack();
            case Freezing -> Items.POWDER_SNOW_BUCKET.getDefaultStack();
            case Lightning -> Items.LIGHTNING_ROD.getDefaultStack();
            case Cactus -> Items.CACTUS.getDefaultStack();
            case Crammed -> Items.MINECART.getDefaultStack();
            case Elytra -> Items.ELYTRA.getDefaultStack();
            case Anvil -> Items.ANVIL.getDefaultStack();
            case Command -> Items.COMMAND_BLOCK.getDefaultStack();
            case Other -> Items.SKELETON_SKULL.getDefaultStack();
        };
    }

    private static int colour(Ui ui, DeathLog.Cause cause) {
        return switch (cause) {
            case Player -> Ui.RED;
            case Mob -> 0xFFFF9A4D;
            case Fire, Lava, Explosion -> 0xFFFFB347;
            default -> ui.fgWell();
        };
    }

    private static String dimension(String id) {
        if (id == null) return "";
        if (id.contains("nether")) return "nether";
        if (id.contains("end")) return "end";
        if (id.contains("overworld")) return "overworld";
        return id;
    }
}
