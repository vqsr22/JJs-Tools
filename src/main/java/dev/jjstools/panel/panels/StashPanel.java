package dev.jjstools.panel.panels;

import dev.jjstools.modules.ControlPanel;
import dev.jjstools.panel.Panel;
import dev.jjstools.panel.PanelData;
import dev.jjstools.panel.Prefs;
import dev.jjstools.panel.StashStore;
import dev.jjstools.panel.StashWalker;
import dev.jjstools.panel.TextBox;
import dev.jjstools.panel.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything in your stash, as one big inventory.
 *
 * Pick a stash at the top, or make one where you stand; every chest, barrel and shulker box you
 * open within its radius from then on is counted. Every kind of item shows once with how many
 * there are in all, shulker boxes' contents included, sorted by count, name or id and narrowed
 * by the creative menu's tabs or a search. Hover one for the count as raw items, stacks,
 * shulkers' worth and dubs (a double chest of full shulkers, 54 of them). Click one to see which
 * containers hold it, outline them in the world, or walk to the best one with Baritone and open
 * it: the one with the most of it that is within walking distance, or failing that the nearest.
 */
public class StashPanel extends Panel {
    private enum Sort { Count, Name, Id }

    private final TextBox search = new TextBox("search");
    private final TextBox newName = new TextBox("name");
    private final TextBox radius = new TextBox("radius");

    private final double[] gridScroll = { 0 };
    private final double[] listScroll = { 0 };
    private final double[] pickScroll = { 0 };

    private boolean picking;
    private boolean naming;
    private int category = -1;
    private StashStore.Kind selected;

    /** The right-click menu: which item it is for and where it opened, or null when closed. */
    private StashStore.Kind menuFor;
    private int menuX, menuY;

    private static List<ItemGroup> groups;
    private static final Map<Item, Integer> CATEGORY = new HashMap<>();

    public StashPanel() {
        super("stash", "stash manager", Items.CHEST.getDefaultStack(), 320, 200);
        newName.max = 40;
        newName.onEnter = this::create;
        radius.max = 7;
        radius.centred = true;
        radius.onEnter = this::applyRadius;
    }

    @Override
    public void shown() {
        buildCategories();
        StashStore.Stash s = active();
        if (s != null) radius.set(String.valueOf(s.radius));
    }

    @Override
    public String badge() {
        StashStore.Stash s = active();
        return s == null ? null : s.name;
    }

    private StashStore.Stash active() {
        Prefs.Data prefs = Prefs.get();
        StashStore.Stash s = StashStore.get(prefs.activeStash);
        if (s == null) {
            for (StashStore.Stash any : StashStore.all()) {
                prefs.activeStash = any.name;
                return any;
            }
        }
        return s;
    }

    /** The creative menu's tabs, for sorting items into kinds as the game does. */
    private static void buildCategories() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (groups != null || mc.world == null) return;
        try {
            ItemGroups.updateDisplayContext(mc.world.getEnabledFeatures(), false, mc.world.getRegistryManager());
            List<ItemGroup> list = new ArrayList<>();
            for (ItemGroup g : ItemGroups.getGroupsToDisplay()) {
                if (g.getType() != ItemGroup.Type.CATEGORY) continue;
                // Nobody keeps a stash of spawn eggs; they go under Other if there are any.
                if (g.getDisplayName().getContent() instanceof net.minecraft.text.TranslatableTextContent t && t.getKey().equals("itemGroup.spawnEggs")) continue;
                list.add(g);
            }
            for (int i = 0; i < list.size(); i++) {
                for (ItemStack s : list.get(i).getDisplayStacks()) CATEGORY.putIfAbsent(s.getItem(), i);
            }
            groups = list;
        } catch (Exception e) {
            groups = List.of();
        }
    }

    @Override
    public void draw(Ui ui, int x, int y, int w, int h) {
        int bh = Math.max(16, ui.lineH() + 7);
        StashStore.Stash stash = active();

        if (stash == null || naming) {
            drawNoStash(ui, x, y, w, h, bh, stash == null);
            return;
        }

        // Header: which stash, and setting it up; five the same width, across the whole panel.
        int gapX = 4;
        int each = (w - gapX * 4) / 5;
        int hx = x;
        int pw = each;
        ui.button(hx, y, pw, bh, stash.name, () -> picking = !picking);
        hx += each + gapX;
        ui.button(hx, y, each, bh, "new", () -> {
            naming = true;
            newName.set("");
            ui.focus = newName;
        });
        hx += each + gapX;
        ui.button(hx, y, each, bh, "set location", MinecraftClient.getInstance().player != null, () -> StashStore.setLocationHere(stash));
        hx += each + gapX;
        ui.textBox(radius, hx, y, each, bh);
        hx += each + gapX;
        ui.button(hx, y, x + w - hx, bh, "set radius", this::applyRadius);

        int top = y + bh + 5;

        // Kinds of item, as the creative menu's tabs.
        int tab = 22;
        int tx = x;
        Prefs.Data p = Prefs.get();
        // Side by side buttons are all the same width, with room to spare, as monkeytype's are.
        int cw = Math.max(ui.width("hide amounts"), ui.width("shulkers: contents")) + 24;
        // Room kept for the other tab and the three buttons after the tabs.
        int tabsEnd = x + w - (cw * 3 + 8 + 6) - (tab + 2);
        ui.iconButton(tx, top, tab, Items.COMPASS.getDefaultStack(), category == -1, "Everything", () -> {
            category = -1;
            gridScroll[0] = 0;
        });
        tx += tab + 2;
        if (groups != null) {
            for (int i = 0; i < groups.size() && tx + tab <= tabsEnd; i++) {
                ItemGroup g = groups.get(i);
                int index = i;
                ui.iconButton(tx, top, tab, g.getIcon(), category == i, g.getDisplayName().getString(), () -> {
                    category = index;
                    gridScroll[0] = 0;
                });
                tx += tab + 2;
            }
        }
        int otherIndex = groups == null ? 0 : groups.size();
        ui.iconButton(tx, top, tab, Items.BARRIER.getDefaultStack(), category == otherIndex, "Other", () -> {
            category = otherIndex;
            gridScroll[0] = 0;
        });
        tx += tab + 6;

        // After the tabs, filling the rest of the row in three equal parts: how shulker boxes count,
        // clearing the outlines in the world, and hiding the amounts on the grid.
        int third = Math.max(cw / 2, (x + w - tx - 8) / 3);
        ui.button(tx, top + 2, third, tab - 4, p.stashContents ? "shulkers: contents" : "shulkers: boxes", () -> {
            p.stashContents = !p.stashContents;
            selected = null;
            menuFor = null;
            Prefs.save();
        });
        ui.button(tx + third + 4, top + 2, third, tab - 4, "clear esp", !PanelData.highlighted.isEmpty(), PanelData.highlighted::clear);
        int amountsX = tx + (third + 4) * 2;
        ui.toggle(amountsX, top + 2, x + w - amountsX, tab - 4, "hide amounts", p.stashHideAmounts, () -> {
            p.stashHideAmounts = !p.stashHideAmounts;
            Prefs.save();
        });

        int gridY = top + tab + 4;
        String categoryName = (category == -1 ? "everything" : category < (groups == null ? 0 : groups.size()) ? groups.get(category).getDisplayName().getString() : "other").toLowerCase(Locale.ROOT);
        ui.text(categoryName, x, gridY, ui.fg());
        gridY += ui.lineH() + 3;

        // What to show: this kind, this search, this order.
        StashStore.ignoreNames = Prefs.get().stashNamesIgnored;
        StashStore.ignoreEnchantments = Prefs.get().stashEnchantsIgnored;
        StashStore.ignoreTrims = Prefs.get().stashTrimsIgnored;
        List<StashStore.Total> all = StashStore.totals(stash, Prefs.get().stashContents);
        List<StashStore.Total> shown = filter(all);
        long totalItems = 0;
        for (StashStore.Total t : shown) totalItems += t.count();

        int bottomH = bh + 4 + ui.lineH() + 2;
        StashStore.Total chosen = null;
        for (StashStore.Total t : all) if (t.kind().equals(selected)) chosen = t;
        int detailH = chosen == null ? 0 : Math.min(h / 2, (bh + 3) * 2 + ui.lineH() * 2 + 8 + Math.min(5, chosen.where().size()) * (bh + 2));
        // A gap between the items and the search and ignore row under them.
        int gridH = h - (gridY - y) - bottomH - 8 - (detailH > 0 ? detailH + 4 : 0);

        drawGrid(ui, x, gridY, w, Math.max(40, gridH), shown, chosen);

        if (chosen != null) drawDetail(ui, x, gridY + Math.max(40, gridH) + 4, w, detailH, chosen, bh);

        // Search and order.
        int by = y + h - bottomH;
        Prefs.Data prefs = Prefs.get();
        Sort sort = sortOf(prefs.stashSort);
        String sortLabel = "sort: " + sort.name().toLowerCase(Locale.ROOT) + " " + (prefs.stashSortReverse ? Ui.UP : Ui.DOWN);
        int even = Math.max(ui.width("ignore enchants"), Math.max(ui.width("ignore names"), ui.width("ignore trims"))) + 20;
        // Narrowed together if the panel is too small for them all at full width.
        int sortW = ui.width("sort: count " + Ui.DOWN) + 20;
        even = Math.max(40, Math.min(even, (w - 90 - sortW) / 3 - 3));
        int inW = even, ieW = even, itW = even;
        int bx = x + w - sortW - 3 - itW - 3 - ieW - 3 - inW - 4;
        ui.textBox(search, x, by, bx - x, bh);
        ui.toggle(bx + 4, by, inW, bh, "ignore names", prefs.stashNamesIgnored, () -> {
            prefs.stashNamesIgnored = !prefs.stashNamesIgnored;
            selected = null;
            menuFor = null;
            Prefs.save();
        });
        ui.toggle(bx + 4 + inW + 3, by, ieW, bh, "ignore enchants", prefs.stashEnchantsIgnored, () -> {
            prefs.stashEnchantsIgnored = !prefs.stashEnchantsIgnored;
            selected = null;
            menuFor = null;
            Prefs.save();
        });
        ui.toggle(bx + 4 + inW + 3 + ieW + 3, by, itW, bh, "ignore trims", prefs.stashTrimsIgnored, () -> {
            prefs.stashTrimsIgnored = !prefs.stashTrimsIgnored;
            selected = null;
            menuFor = null;
            Prefs.save();
        });
        ui.button(x + w - sortW, by, sortW, bh, sortLabel, () -> {
            prefs.stashSort = switch (sort) {
                case Count -> "Name";
                case Name -> "Id";
                case Id -> "Count";
            };
        });
        ui.onClick(x + w - sortW, by, sortW, bh, 1, b -> prefs.stashSortReverse = !prefs.stashSortReverse);

        long updated = stash.lastUpdated();
        String summary = stash.count() + " containers" + (updated > 0 ? "  \u00B7  updated " + ChatPanel.ago(updated) + " ago" : "")
            + "  \u00B7  " + Ui.grouped(totalItems) + " items" + (stash.located ? "" : "  \u00B7  no location set")
            + (ControlPanel.syncMode() == ControlPanel.SyncMode.Server ? "  \u00B7  " + dev.jjstools.panel.StashSync.status() : "");
        ui.small(ui.fitSmall(summary, w), x, by + bh + 4, ui.fgDim());

        if (picking) drawPicker(ui, x, y + bh, Math.max(pw, 160), Math.min(h - bh, 10 * (bh + 2) + 6), bh);

        StashStore.Total menuTotal = null;
        for (StashStore.Total t : all) if (t.kind().equals(menuFor)) menuTotal = t;
        if (menuTotal != null) drawMenu(ui, x, y, w, h, menuTotal, bh);
        else menuFor = null;
    }

    private void drawNoStash(Ui ui, int x, int y, int w, int h, int bh, boolean none) {
        int cy = y + 6;
        if (none) {
            ui.text("no stashes yet", x, cy, ui.fg());
            cy += ui.lineH() + 4;
            for (String line : ui.wrap("Stand in your stash, give it a name and press create. Every chest, barrel and shulker box you open within "
                + ControlPanel.newStashRadius() + " blocks of here is then recorded. Stash files live in "
                + StashStore.folder() + ". Set stash-folder in the Control Panel module to a synced folder to share them.", w)) {
                ui.text(line, x, cy, ui.fgDim());
                cy += ui.lineH() + 2;
            }
        } else {
            ui.text("new stash, centred where you are standing", x, cy, ui.fg());
            cy += ui.lineH() + 4;
        }
        cy += 4;
        int cw = Math.max(ui.width("create"), ui.width("cancel")) + 24;
        ui.textBox(newName, x, cy, Math.min(240, w - cw * 2 - 8), bh);
        int bx = x + Math.min(240, w - cw * 2 - 8) + 4;
        ui.button(bx, cy, cw, bh, "create", !newName.text.isBlank() && MinecraftClient.getInstance().player != null, this::create);
        if (!none) ui.button(bx + cw + 4, cy, cw, bh, "cancel", () -> naming = false);
    }

    private void create() {
        String name = newName.text.trim();
        if (name.isEmpty() || MinecraftClient.getInstance().player == null) return;
        if (StashStore.get(name) == null) StashStore.create(name);
        Prefs.get().activeStash = name;
        Prefs.save();
        naming = false;
        radius.set(String.valueOf(StashStore.get(name).radius));
    }

    private void applyRadius() {
        StashStore.Stash s = active();
        if (s == null) return;
        try {
            StashStore.setRadius(s, Integer.parseInt(radius.text.trim()));
        } catch (NumberFormatException ignored) {
            // Left as it was.
        }
        radius.set(String.valueOf(s.radius));
    }

    private void drawPicker(Ui ui, int x, int y, int w, int h, int bh) {
        List<StashStore.Stash> list = new ArrayList<>(StashStore.all());
        list.sort(Comparator.comparing(s -> s.name.toLowerCase(Locale.ROOT)));
        int step = bh + 2;
        int contentH = list.size() * step + step + 6;
        int boxH = Math.min(h, contentH);
        ui.panel(x, y, w, boxH);
        ui.onClick(x, y, w, boxH, -1, b -> {});
        ui.clip(x + 3, y + 3, w - 6, boxH - 6);
        int off = ui.scrollArea(x + 3, y + 3, w - 6, boxH - 6, contentH - 6, pickScroll, step);
        int py = y + 3 - off;
        for (StashStore.Stash s : list) {
            boolean active = s.name.equalsIgnoreCase(Prefs.get().activeStash);
            ui.toggle(x + 3, py, w - 6 - 8, bh, s.name + "  (" + s.count() + ")", active, () -> {
                Prefs.get().activeStash = s.name;
                radius.set(String.valueOf(s.radius));
                selected = null;
                PanelData.highlighted.clear();
                picking = false;
                Prefs.save();
            });
            py += step;
        }
        StashStore.Stash current = active();
        if (current != null) {
            ui.button(x + 3, py, w - 6 - 8, bh, "delete " + current.name, () -> {
                StashStore.delete(current);
                Prefs.get().activeStash = null;
                picking = false;
                selected = null;
                PanelData.highlighted.clear();
            });
        }
        ui.unclip();
    }

    private static Sort sortOf(String s) {
        if ("Name".equals(s)) return Sort.Name;
        if ("Id".equals(s)) return Sort.Id;
        return Sort.Count;
    }

    private List<StashStore.Total> filterFor;
    private String filterKey;
    private List<StashStore.Total> filtered = List.of();

    /** The filtered list, worked out again only when the totals, search, tab or order change. */
    private List<StashStore.Total> filter(List<StashStore.Total> all) {
        Prefs.Data p = Prefs.get();
        String key = search.text + "|" + category + "|" + p.stashSort + "|" + p.stashSortReverse;
        if (all != filterFor || !key.equals(filterKey)) {
            filterFor = all;
            filterKey = key;
            filtered = filterNow(all);
        }
        return filtered;
    }

    private List<StashStore.Total> filterNow(List<StashStore.Total> all) {
        String q = search.text.trim().toLowerCase(Locale.ROOT);
        String namespace = null;
        if (q.startsWith("@")) {
            int space = q.indexOf(' ');
            namespace = space < 0 ? q.substring(1) : q.substring(1, space);
            q = space < 0 ? "" : q.substring(space + 1).trim();
        }
        int others = groups == null ? 0 : groups.size();

        List<StashStore.Total> list = new ArrayList<>();
        for (StashStore.Total t : all) {
            if (category >= 0) {
                int c = CATEGORY.getOrDefault(t.item(), others);
                if (c != category) continue;
            }
            Identifier id = Registries.ITEM.getId(t.item());
            if (namespace != null && !id.getNamespace().startsWith(namespace)) continue;
            if (!q.isEmpty()) {
                String name = t.sample().getName().getString().toLowerCase(Locale.ROOT);
                if (!name.contains(q) && !id.getPath().contains(q.replace(' ', '_'))) continue;
            }
            list.add(t);
        }

        Prefs.Data prefs = Prefs.get();
        Comparator<StashStore.Total> order = switch (sortOf(prefs.stashSort)) {
            case Count -> Comparator.comparingLong((StashStore.Total t) -> t.count()).reversed();
            case Name -> Comparator.comparing(t -> t.sample().getName().getString().toLowerCase(Locale.ROOT));
            case Id -> Comparator.comparing(t -> Registries.ITEM.getId(t.item()).toString());
        };
        if (prefs.stashSortReverse) order = order.reversed();
        list.sort(order);
        return list;
    }

    /**
     * The grid of items.
     *
     * Drawn in passes, all the slots, then all the items, then the shulker extras, then the
     * counts, rather than slot by slot. The game keeps everything drawn in layers and checks each
     * new thing against what is already there, so the grid used to cost a new layer per shulker
     * and three boxes per slot. Done this way it is a handful of strips and a few layers however
     * big the stash is, and one click area for the whole grid.
     */
    private void drawGrid(Ui ui, int x, int y, int w, int h, List<StashStore.Total> shown, StashStore.Total chosen) {
        ui.well(x, y, w, h);
        int cols = Math.max(1, (w - 4 - 8) / 18);
        int rows = (shown.size() + cols - 1) / cols;
        int contentH = rows * 18 + 4;

        ui.clip(x + 1, y + 1, w - 2, h - 2);
        int off = ui.scrollArea(x + 1, y + 1, w - 2, h - 2, contentH, gridScroll, 18);
        int gx = x + 2;
        int gy = y + 2 - off;

        if (shown.isEmpty()) {
            ui.centredWrapped("Nothing here yet. Open the containers in your stash to fill it.", x, w, y + h / 2, ui.fgWellDim());
        }

        // Only the rows that can be seen.
        int firstRow = Math.max(0, (y - gy) / 18);
        int lastRow = Math.min(rows, (y + h - gy) / 18 + 1);
        int from = Math.min(shown.size(), firstRow * cols);
        int to = Math.min(shown.size(), lastRow * cols);
        if (from < to) {
            ui.slotGrid(gx, gy + firstRow * 18, cols, to - from);

            for (int i = from; i < to; i++) {
                ui.item(shown.get(i).sample(), gx + (i % cols) * 18 + 1, gy + (i / cols) * 18 + 1);
            }
            // Storage GUI Tweaks' icon and fill bar on shulker boxes, as in an inventory.
            for (int i = from; i < to; i++) {
                dev.jjstools.modules.StorageGuiTweaks.slotExtras(ui.ctx, shown.get(i).sample(), gx + (i % cols) * 18 + 1, gy + (i / cols) * 18 + 1);
                ui.added(2);
            }
            for (int i = from; i < to && !Prefs.get().stashHideAmounts; i++) {
                StashStore.Total t = shown.get(i);
                ui.itemCount(t.sample(), gx + (i % cols) * 18 + 1, gy + (i / cols) * 18 + 1, Ui.shortCount(t.count()));
            }
        }

        int chosenAt = chosen == null ? -1 : shown.indexOf(chosen);
        if (chosenAt >= from && chosenAt < to) {
            int sx = gx + (chosenAt % cols) * 18;
            int sy = gy + (chosenAt / cols) * 18;
            ui.fill(sx, sy, 18, 1, ui.accent());
            ui.fill(sx, sy + 17, 18, 1, ui.accent());
            ui.fill(sx, sy, 1, 18, ui.accent());
            ui.fill(sx + 17, sy, 1, 18, ui.accent());
        }

        int under = slotUnder(ui.mx, ui.my, x, y, w, h, gx, gy, cols, shown.size());
        if (under >= 0) {
            StashStore.Total t = shown.get(under);
            ui.fill(gx + (under % cols) * 18 + 1, gy + (under / cols) * 18 + 1, 16, 16, 0x80FFFFFF);
            ui.itemTooltip(t.sample(), tooltip(t));
        }

        ui.onClick(x + 1, y + 1, w - 2, h - 2, -1, b -> {
            int i = slotUnder(ui.mx, ui.my, x, y, w, h, gx, gy, cols, shown.size());
            if (i < 0) return;
            StashStore.Total t = shown.get(i);
            if (b == 0) {
                selected = t.kind();
                listScroll[0] = 0;
                menuFor = null;
                highlight(t);
                if (shift()) walk(t);
            } else if (b == 1) {
                menuFor = t.kind();
                menuX = ui.mx;
                menuY = ui.my;
            }
        });
        ui.unclip();
    }

    /** Which item in the grid is at a point, or -1. */
    private static int slotUnder(int mx, int my, int x, int y, int w, int h, int gx, int gy, int cols, int size) {
        if (mx < x + 1 || my < y + 1 || mx >= x + w - 1 || my >= y + h - 1) return -1;
        if (mx < gx || my < gy) return -1;
        int col = (mx - gx) / 18;
        int row = (my - gy) / 18;
        if (col >= cols) return -1;
        int i = row * cols + col;
        return i < size ? i : -1;
    }

    private static boolean shift() {
        long window = MinecraftClient.getInstance().getWindow().getHandle();
        return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
    }

    private static List<Text> tooltip(StashStore.Total t) {
        List<Text> lines = new ArrayList<>();
        lines.add(t.sample().getName());
        List<Text> enchants = enchantments(t.sample());
        if (StashStore.ignoreEnchantments && !enchants.isEmpty()) lines.add(Text.literal("any enchantments").formatted(Formatting.GRAY));
        else lines.addAll(enchants);
        int max = Math.max(1, t.sample().getMaxCount());
        double stacks = t.count() / (double) max;
        double shulkers = t.count() / (27.0 * max);
        double dubs = t.count() / (54.0 * 27 * max);
        lines.add(Text.literal("total: ").formatted(Formatting.GRAY).append(Text.literal(Ui.grouped(t.count())).formatted(Formatting.GREEN)));
        lines.add(Text.literal("stacks: ").formatted(Formatting.GRAY).append(Text.literal(stacks(t.count(), max)).formatted(Formatting.WHITE)));
        lines.add(Text.literal("shulkers: ").formatted(Formatting.GRAY).append(Text.literal(Ui.decimal(shulkers)).formatted(Formatting.WHITE)));
        lines.add(Text.literal("dubs: ").formatted(Formatting.GRAY).append(Text.literal(Ui.decimal(dubs)).formatted(Formatting.WHITE)));
        lines.add(Text.literal("in " + t.where().size() + " container" + (t.where().size() == 1 ? "" : "s")).formatted(Formatting.YELLOW));
        lines.add(Text.literal(Registries.ITEM.getId(t.item()).toString()).formatted(Formatting.DARK_GRAY));
        return lines;
    }

    /** Whole stacks and what is left over: "8 Stacks + 31", "1 Stack", "12". */
    private static String stacks(long count, int max) {
        if (max <= 1) return Ui.grouped(count);
        long whole = count / max;
        long rest = count % max;
        if (whole == 0) return String.valueOf(rest);
        String s = Ui.grouped(whole) + (whole == 1 ? " stack" : " stacks");
        return rest == 0 ? s : s + " + " + rest;
    }

    /** An item's enchantments, stored enchantments for a book, as tooltip lines. */
    private static List<Text> enchantments(ItemStack stack) {
        List<Text> lines = new ArrayList<>();
        for (var component : new ItemEnchantmentsComponent[] {
            stack.get(DataComponentTypes.ENCHANTMENTS), stack.get(DataComponentTypes.STORED_ENCHANTMENTS) }) {
            if (component == null) continue;
            for (var entry : component.getEnchantmentEntries()) {
                lines.add(Enchantment.getName(entry.getKey(), entry.getIntValue()).copy().formatted(Formatting.GRAY));
            }
        }
        return lines;
    }

    /** The right-click menu for an item: everything that can be done with it in one place. */
    private void drawMenu(Ui ui, int x, int y, int w, int h, StashStore.Total t, int bh) {
        StashStore.Holding best = StashStore.best(t, ControlPanel.walkDistance());
        boolean hide = Prefs.get().hideCoords;
        String[] labels = { "baritone walk", "show locations", "esp containers", "clear esp", hide ? null : "copy coords" };

        int mw = 0;
        int count = 0;
        for (String l : labels) {
            if (l == null) continue;
            mw = Math.max(mw, ui.width(l));
            count++;
        }
        mw += 16;
        int step = bh;
        int mh = count * step + 4 + ui.lineH() + 4;
        int mx = Math.min(menuX, x + w - mw);
        int my = Math.min(menuY, y + h - mh);

        // Clicking anywhere else closes it.
        ui.onClick(x, y, w, h, -1, b -> menuFor = null);
        ui.panel(mx, my, mw, mh);
        ui.onClick(mx, my, mw, mh, -1, b -> {});
        ui.text(ui.fit(t.sample().getName().getString(), mw - 8), mx + 5, my + 4, ui.fg());
        int ry = my + ui.lineH() + 7;
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == null) continue;
            int action = i;
            boolean enabled = action != 0 && action != 4 || best != null;
            ui.toggle(mx + 2, ry, mw - 4, step, labels[i], false, enabled ? () -> {
                switch (action) {
                    case 0 -> walk(t);
                    case 1 -> {
                        selected = t.kind();
                        listScroll[0] = 0;
                        highlight(t);
                    }
                    case 2 -> highlight(t);
                    case 3 -> PanelData.highlighted.clear();
                    case 4 -> {
                        BlockPos p = best.container().pos();
                        MinecraftClient.getInstance().keyboard.setClipboard(p.getX() + " " + p.getY() + " " + p.getZ());
                    }
                    default -> { }
                }
                menuFor = null;
            } : null);
            ry += step;
        }
    }

    private void drawDetail(Ui ui, int x, int y, int w, int h, StashStore.Total t, int bh) {
        ui.well(x, y, w, h);
        int cx = x + 4;
        int cy = y + 4;
        ui.slot(cx, cy);
        ui.item(t.sample(), cx + 1, cy + 1);
        int max = Math.max(1, t.sample().getMaxCount());
        ui.text(t.sample().getName().getString(), cx + 22, cy, ui.fgWell());
        String numbers = Ui.grouped(t.count()) + " items  -  " + stacks(t.count(), max) + "  -  "
            + Ui.decimal(t.count() / (27.0 * max)) + " shulkers  -  " + Ui.decimal(t.count() / (54.0 * 27 * max)) + " dubs";
        ui.small(ui.fitSmall(numbers, w - 30), cx + 22, cy + ui.lineH() + 3, ui.fgWellDim());

        StashStore.Holding best = StashStore.best(t, ControlPanel.walkDistance());
        int by = cy + Math.max(18, ui.lineH() * 2 + 4) + 4;
        int bx = cx;
        String walkLabel = StashWalker.target() != null ? "stop walking" : "baritone walk";
        int ww = Math.max(Math.max(ui.width("stop walking"), ui.width("baritone walk")), Math.max(ui.width("esp containers"), ui.width("clear esp"))) + 24;
        boolean hover = ui.button(bx, by, ww, bh, walkLabel, best != null, () -> {
            if (StashWalker.target() != null) StashWalker.stop();
            else walk(t);
        });
        if (hover && best != null) ui.tooltip("Walks to " + (Prefs.get().hideCoords ? "the best container" : best.container().pos().toShortString()) + " (" + Ui.grouped(best.count()) + " there) and opens it.");
        bx += ww + 4;
        int hw = ww;
        ui.button(bx, by, hw, bh, PanelData.highlighted.isEmpty() ? "esp containers" : "clear esp", () -> {
            if (PanelData.highlighted.isEmpty()) highlight(t);
            else PanelData.highlighted.clear();
        });
        bx += hw + 4;
        ui.button(bx, by, ww, bh, "close", () -> {
            selected = null;
            PanelData.highlighted.clear();
        });

        // Where it is, most first.
        int ly = by + bh + 4;
        int lh = y + h - ly - 3;
        int step = bh + 2;
        ui.clip(x + 1, ly, w - 2, lh);
        int off = ui.scrollArea(x + 1, ly, w - 2, lh, t.where().size() * step, listScroll, step);
        int ry = ly - off;
        MinecraftClient mc = MinecraftClient.getInstance();
        StashStore.Stash stash = active();
        for (StashStore.Holding hold : t.where()) {
            if (ry + step < ly || ry > ly + lh) {
                ry += step;
                continue;
            }
            BlockPos pos = hold.container().pos();
            String dist = mc.player == null ? "" : Ui.grouped((long) Math.sqrt(mc.player.getBlockPos().getSquaredDistance(pos))) + "m";
            boolean isBest = best != null && hold == best;
            String at = Prefs.get().hideCoords ? "" : " at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
            String line = (isBest ? "best  \u00B7  " : "") + hold.container().kind.toLowerCase(Locale.ROOT) + at
                + "  -  " + Ui.grouped(hold.count()) + "  -  " + dist
                + (hold.container().by != null ? "  -  by " + hold.container().by + " " + ChatPanel.ago(hold.container().updated) + " ago" : "");
            int goW = Math.max(ui.width("go"), ui.width("forget")) + 20;
            int fw = goW;
            ui.text(ui.fit(line, w - goW - fw - 16), x + 6, ry + (bh - ui.lineH()) / 2 + 1, isBest ? Ui.GREEN : ui.fgWell());
            ui.button(x + w - goW - fw - 12, ry, goW, bh, "go", () -> StashWalker.go(pos));
            ui.button(x + w - fw - 8, ry, fw, bh, "forget", () -> {
                if (stash != null) StashStore.forget(stash, hold.container());
            });
            ry += step;
        }
        ui.unclip();
    }

    private static void highlight(StashStore.Total t) {
        PanelData.highlighted.clear();
        for (StashStore.Holding hold : t.where()) {
            if (PanelData.highlighted.size() >= 512) break;
            PanelData.highlighted.add(hold.container().pos());
        }
    }

    private static void walk(StashStore.Total t) {
        StashStore.Holding best = StashStore.best(t, ControlPanel.walkDistance());
        if (best != null) StashWalker.go(best.container().pos());
    }

    private static String shortDimension(String id) {
        if (id == null) return "?";
        return switch (id) {
            case "minecraft:overworld" -> "the Overworld";
            case "minecraft:the_nether" -> "the Nether";
            case "minecraft:the_end" -> "the End";
            default -> id;
        };
    }
}
