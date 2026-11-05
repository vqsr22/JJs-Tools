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

    private final TextBox search = new TextBox("Search (e.g. diamond sword, @minecraft)");
    private final TextBox newName = new TextBox("Stash name");
    private final TextBox radius = new TextBox("Radius");

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
        super("stash", "Stash Manager", Items.CHEST.getDefaultStack(), 320, 200);
        newName.max = 40;
        newName.onEnter = this::create;
        radius.max = 7;
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

        // Header: which stash, where it is, and setting it up.
        int hx = x;
        String label = stash.name;
        int pw = Math.max(90, Math.min(180, ui.width(label) + 16));
        ui.button(hx, y, pw, bh, label, () -> picking = !picking);
        hx += pw + 4;
        int nw = ui.width("New") + 14;
        ui.button(hx, y, nw, bh, "New", () -> {
            naming = true;
            newName.set("");
            ui.focus = newName;
        });
        hx += nw + 4;
        int sw = ui.width("Set location here") + 14;
        boolean set = ui.button(hx, y, sw, bh, "Set location here", MinecraftClient.getInstance().player != null, () -> StashStore.setLocationHere(stash));
        if (set) ui.tooltip("Centres this stash where you are standing. Every container opened within the radius is recorded.");
        hx += sw + 6;
        ui.text("Radius", hx, y + (bh - ui.lineH()) / 2 + 1, ui.fgDim());
        hx += ui.width("Radius") + 4;
        int rw = ui.width("000000") + 12;
        ui.textBox(radius, hx, y, rw, bh);
        hx += rw + 3;
        ui.button(hx, y, ui.width("Set") + 12, bh, "Set", this::applyRadius);

        boolean hideCoords = Prefs.get().hideCoords;
        int iy = y + bh + 4;
        String where = stash.located
            ? (hideCoords ? "" : "centre " + stash.x + ", " + stash.z + " in ") + shortDimension(stash.dimension) + " on " + stash.server
            : "no location yet: stand in your stash and press Set location here";
        long updated = stash.lastUpdated();
        String info = stash.count() + " containers" + (updated > 0 ? "  -  updated " + ChatPanel.ago(updated) + " ago" : "") + "  -  " + where;
        ui.small(ui.fitSmall(info, w), x, iy, ui.fgDim());
        int distanceY = iy + ui.smallH() + 2;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (stash.located && mc.player != null) {
            double dx = mc.player.getX() - stash.x, dz = mc.player.getZ() - stash.z;
            int dist = (int) Math.sqrt(dx * dx + dz * dz);
            boolean inside = dist <= stash.radius;
            String status = inside ? "In this stash, " + Ui.grouped(dist) + " blocks from its centre: containers you open are recorded"
                : Ui.grouped(dist) + " blocks away, outside its radius";
            ui.small(ui.fitSmall(status, w), x, distanceY, inside ? Ui.GREEN : ui.fgDim());
        }
        int top = distanceY + ui.smallH() + 5;

        // Kinds of item, as the creative menu's tabs.
        int tab = 22;
        int tx = x;
        ui.iconButton(tx, top, tab, Items.COMPASS.getDefaultStack(), category == -1, "Everything", () -> {
            category = -1;
            gridScroll[0] = 0;
        });
        tx += tab + 2;
        if (groups != null) {
            for (int i = 0; i < groups.size() && tx + tab <= x + w - (ui.width("Clear outlines") + ui.width("Shulkers: contents") + 40); i++) {
                ItemGroup g = groups.get(i);
                int index = i;
                ui.iconButton(tx, top, tab, g.getIcon(), category == i, g.getDisplayName().getString(), () -> {
                    category = index;
                    gridScroll[0] = 0;
                });
                tx += tab + 2;
            }
        }
        // Right of the tabs: how shulker boxes count, and clearing the outlines in the world.
        Prefs.Data p = Prefs.get();
        int cw = ui.width("Clear outlines") + 14;
        int mw = ui.width("Shulkers: contents") + 14;
        int right = x + w;
        boolean clearHover = ui.button(right - cw, top + 2, cw, tab - 4, "Clear outlines", !PanelData.highlighted.isEmpty(), PanelData.highlighted::clear);
        if (clearHover) ui.tooltip("Takes every stash outline out of the world.");
        right -= cw + 4;
        boolean modeHover = ui.button(right - mw, top + 2, mw, tab - 4, p.stashContents ? "Shulkers: contents" : "Shulkers: boxes", () -> {
            p.stashContents = !p.stashContents;
            selected = null;
            menuFor = null;
            Prefs.save();
        });
        if (modeHover) ui.tooltip(p.stashContents ? "Counting what is inside shulker boxes. Click to count the boxes themselves." : "Counting shulker boxes as boxes. Click to count what is inside them.");
        int tabsEnd = right - mw - 6;

        int otherIndex = groups == null ? 0 : groups.size();
        if (tx + tab <= tabsEnd) {
            ui.iconButton(tx, top, tab, Items.BARRIER.getDefaultStack(), category == otherIndex, "Other", () -> {
                category = otherIndex;
                gridScroll[0] = 0;
            });
        }

        int gridY = top + tab + 4;
        String categoryName = category == -1 ? "Everything" : category < (groups == null ? 0 : groups.size()) ? groups.get(category).getDisplayName().getString() : "Other";
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
        int gridH = h - (gridY - y) - bottomH - (detailH > 0 ? detailH + 4 : 0);

        drawGrid(ui, x, gridY, w, Math.max(40, gridH), shown, chosen);

        if (chosen != null) drawDetail(ui, x, gridY + Math.max(40, gridH) + 4, w, detailH, chosen, bh);

        // Search and order.
        int by = y + h - bottomH;
        Prefs.Data prefs = Prefs.get();
        Sort sort = sortOf(prefs.stashSort);
        String sortLabel = "Sort: " + (sort == Sort.Id ? "ID" : sort.name()) + " " + (prefs.stashSortReverse ? Ui.UP : Ui.DOWN);
        int sortW = ui.width("Sort: Count " + Ui.DOWN) + 14;
        int inW = ui.width("Ignore names") + 12;
        int ieW = ui.width("Ignore enchants") + 12;
        int itW = ui.width("Ignore trims") + 12;
        int bx = x + w - sortW - 4 - itW - 2 - ieW - 2 - inW - 4;
        ui.textBox(search, x, by, bx - x, bh);
        boolean namesHover = ui.toggle(bx + 4, by, inW, bh, "Ignore names", prefs.stashNamesIgnored, () -> {
            prefs.stashNamesIgnored = !prefs.stashNamesIgnored;
            selected = null;
            menuFor = null;
            Prefs.save();
        });
        if (namesHover) ui.tooltip("Counts renamed items together with the rest of their kind.");
        boolean enchHover = ui.toggle(bx + 4 + inW + 2, by, ieW, bh, "Ignore enchants", prefs.stashEnchantsIgnored, () -> {
            prefs.stashEnchantsIgnored = !prefs.stashEnchantsIgnored;
            selected = null;
            menuFor = null;
            Prefs.save();
        });
        if (enchHover) ui.tooltip("Counts enchanted items together whatever their enchantments. Enchanted and plain items are still kept apart.");
        boolean trimHover = ui.toggle(bx + 4 + inW + 2 + ieW + 2, by, itW, bh, "Ignore trims", prefs.stashTrimsIgnored, () -> {
            prefs.stashTrimsIgnored = !prefs.stashTrimsIgnored;
            selected = null;
            menuFor = null;
            Prefs.save();
        });
        if (trimHover) ui.tooltip("Counts trimmed armour together whatever its trim. Trimmed and plain armour are still kept apart.");
        boolean hover = ui.button(x + w - sortW, by, sortW, bh, sortLabel, () -> {
            prefs.stashSort = switch (sort) {
                case Count -> "Name";
                case Name -> "Id";
                case Id -> "Count";
            };
        });
        if (hover) ui.tooltip("Click to change what it sorts by; right-click to flip the order.");
        ui.onClick(x + w - sortW, by, sortW, bh, 1, b -> prefs.stashSortReverse = !prefs.stashSortReverse);

        String summary = "Showing " + shown.size() + " of " + all.size() + " item types  -  total " + Ui.grouped(totalItems) + " items"
            + "  -  click an item for where it is, right-click for more";
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
            ui.text("No stashes yet.", x, cy, ui.fg());
            cy += ui.lineH() + 4;
            for (String line : ui.wrap("Stand in your stash, give it a name and press Create. Every chest, barrel and shulker box you open within "
                + ControlPanel.newStashRadius() + " blocks of here is then recorded. Stash files live in "
                + StashStore.folder() + "; set Stash Folder in Control Panel's module settings to a synced folder to share them.", w)) {
                ui.text(line, x, cy, ui.fgDim());
                cy += ui.lineH() + 2;
            }
        } else {
            ui.text("New stash, centred where you are standing:", x, cy, ui.fg());
            cy += ui.lineH() + 4;
        }
        cy += 4;
        int cw = ui.width("Create") + 16;
        ui.textBox(newName, x, cy, Math.min(240, w - cw * 2 - 8), bh);
        int bx = x + Math.min(240, w - cw * 2 - 8) + 4;
        ui.button(bx, cy, cw, bh, "Create", !newName.text.isBlank() && MinecraftClient.getInstance().player != null, this::create);
        if (!none) ui.button(bx + cw + 4, cy, cw, bh, "Cancel", () -> naming = false);
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
            ui.button(x + 3, py, w - 6 - 8, bh, "Delete " + current.name, () -> {
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

    private List<StashStore.Total> filter(List<StashStore.Total> all) {
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

        for (int i = 0; i < shown.size(); i++) {
            int sx = gx + (i % cols) * 18;
            int sy = gy + (i / cols) * 18;
            if (sy + 18 < y || sy > y + h) continue;

            StashStore.Total t = shown.get(i);
            ui.slot(sx, sy);
            ui.item(t.sample(), sx + 1, sy + 1);
            // Storage GUI Tweaks' icon and fill bar on shulker boxes, as in an inventory.
            dev.jjstools.modules.StorageGuiTweaks.slotExtras(ui.ctx, t.sample(), sx + 1, sy + 1);
            ui.itemCount(t.sample(), sx + 1, sy + 1, Ui.shortCount(t.count()));
            if (t == chosen) {
                ui.fill(sx, sy, 18, 1, ui.accent());
                ui.fill(sx, sy + 17, 18, 1, ui.accent());
                ui.fill(sx, sy, 1, 18, ui.accent());
                ui.fill(sx + 17, sy, 1, 18, ui.accent());
            }
            if (ui.hovered(sx, sy, 18, 18)) {
                ui.fill(sx + 1, sy + 1, 16, 16, 0x80FFFFFF);
                ui.itemTooltip(t.sample(), tooltip(t));
            }
            ui.onClick(sx, sy, 18, 18, 0, b -> {
                selected = t.kind();
                listScroll[0] = 0;
                menuFor = null;
                highlight(t);
                if (shift()) walk(t);
            });
            ui.onClick(sx, sy, 18, 18, 1, b -> {
                menuFor = t.kind();
                menuX = ui.mx;
                menuY = ui.my;
            });
        }
        ui.unclip();
    }

    private static boolean shift() {
        long window = MinecraftClient.getInstance().getWindow().getHandle();
        return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
    }

    private static List<Text> tooltip(StashStore.Total t) {
        List<Text> lines = new ArrayList<>();
        lines.add(t.sample().getName());
        List<Text> enchants = enchantments(t.sample());
        if (StashStore.ignoreEnchantments && !enchants.isEmpty()) lines.add(Text.literal("Enchanted, any enchantments").formatted(Formatting.GRAY));
        else lines.addAll(enchants);
        int max = Math.max(1, t.sample().getMaxCount());
        double stacks = t.count() / (double) max;
        double shulkers = t.count() / (27.0 * max);
        double dubs = t.count() / (54.0 * 27 * max);
        lines.add(Text.literal("Total: ").formatted(Formatting.GRAY).append(Text.literal(Ui.grouped(t.count())).formatted(Formatting.GREEN)));
        lines.add(Text.literal("Stacks: ").formatted(Formatting.GRAY).append(Text.literal(stacks(t.count(), max)).formatted(Formatting.WHITE)));
        lines.add(Text.literal("Shulkers: ").formatted(Formatting.GRAY).append(Text.literal(Ui.decimal(shulkers)).formatted(Formatting.WHITE)));
        lines.add(Text.literal("Dubs: ").formatted(Formatting.GRAY).append(Text.literal(Ui.decimal(dubs)).formatted(Formatting.WHITE)));
        lines.add(Text.literal("In " + t.where().size() + " container" + (t.where().size() == 1 ? "" : "s")).formatted(Formatting.YELLOW));
        lines.add(Text.literal(Registries.ITEM.getId(t.item()).toString()).formatted(Formatting.DARK_GRAY));
        return lines;
    }

    /** Whole stacks and what is left over: "8 Stacks + 31", "1 Stack", "12". */
    private static String stacks(long count, int max) {
        if (max <= 1) return Ui.grouped(count);
        long whole = count / max;
        long rest = count % max;
        if (whole == 0) return String.valueOf(rest);
        String s = Ui.grouped(whole) + (whole == 1 ? " Stack" : " Stacks");
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
        String[] labels = { "Walk to best and open", "Show where it is", "Outline in the world", "Clear outlines", hide ? null : "Copy best coordinates" };

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
        String walkLabel = StashWalker.target() != null ? "Stop walking" : "Walk to best";
        int ww = ui.width("Walk to best") + 16;
        boolean hover = ui.button(bx, by, ww, bh, walkLabel, best != null, () -> {
            if (StashWalker.target() != null) StashWalker.stop();
            else walk(t);
        });
        if (hover && best != null) ui.tooltip("Walks with Baritone to " + (Prefs.get().hideCoords ? "the best container" : best.container().pos().toShortString()) + " (" + Ui.grouped(best.count()) + " there) and opens it.");
        bx += ww + 4;
        int hw = ui.width("Clear outlines") + 16;
        ui.button(bx, by, hw, bh, PanelData.highlighted.isEmpty() ? "Outline all" : "Clear outlines", () -> {
            if (PanelData.highlighted.isEmpty()) highlight(t);
            else PanelData.highlighted.clear();
        });
        bx += hw + 4;
        ui.button(bx, by, ui.width("Close") + 16, bh, "Close", () -> {
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
            String line = (isBest ? "Best: " : "") + hold.container().kind + at
                + "  -  " + Ui.grouped(hold.count()) + "  -  " + dist
                + (hold.container().by != null ? "  -  by " + hold.container().by + " " + ChatPanel.ago(hold.container().updated) + " ago" : "");
            int goW = ui.width("Go") + 12;
            int fw = ui.width("Forget") + 12;
            ui.text(ui.fit(line, w - goW - fw - 16), x + 6, ry + (bh - ui.lineH()) / 2 + 1, isBest ? Ui.GREEN : ui.fgWell());
            ui.button(x + w - goW - fw - 12, ry, goW, bh, "Go", () -> StashWalker.go(pos));
            ui.button(x + w - fw - 8, ry, fw, bh, "Forget", () -> {
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
