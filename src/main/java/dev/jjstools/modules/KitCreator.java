package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import dev.jjstools.util.KitStore;
import dev.jjstools.util.MsgUtil;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import meteordevelopment.orbit.EventHandler;
import org.lwjgl.glfw.GLFW;

/**
 * Remembers what was in a shulker box and refills another one the same way.
 *
 * Scan writes down every slot of the open box: what the item was, where it sat and what was
 * enchanted on it. Create then makes the open box match that list. Items already inside are moved
 * into the slots they were scanned in, missing ones are pulled out of your inventory, and anything
 * the scan does not account for is shifted back out, so a half sorted box ends up laid out exactly
 * like the one you scanned.
 *
 * Matching is by item and enchantments only. Names, lore and durability are ignored on purpose, so
 * a fresh Mending pick fills the slot a renamed, half worn one came out of. Anything you are short
 * of is skipped and its slot left empty rather than filled with the wrong thing.
 *
 * One item is moved per tick, and the list is recalculated from the box's live contents every time
 * rather than trusted from the first pass. Queueing up a whole box of clicks is what makes the
 * server start correcting them, and a correction halfway through a cached plan puts items in the
 * wrong slots.
 */
public class KitCreator extends Module {
    /** Slots in a player's inventory: 27 main plus 9 hotbar, always on the end of a container. */
    private static final int PLAYER_SLOTS = 36;

    private static final int MAX_ATTEMPTS = 3;

    /**
     * A left click, as a keybind.
     *
     * The mouse event carries an input object rather than a button number, and asking a keybind
     * whether it matches is the way to read one without unpacking it.
     */
    private static final Keybind LEFT_CLICK = Keybind.fromButton(GLFW.GLFW_MOUSE_BUTTON_LEFT);

    private static final int BUTTON_W = 54;
    private static final int BUTTON_W_INSIDE = 40;
    private static final int BUTTON_H = 14;
    private static final int BUTTON_H_INSIDE = 12;
    private static final int BUTTON_GAP = 3;

    private static final int FACE = 0xFF2B2B2B;
    private static final int FACE_HOVER = 0xFF4F4F4F;
    private static final int FACE_OFF = 0xFF1E1E1E;
    private static final int EDGE = 0xFF8B8B8B;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_OFF = 0xFF7A7A7A;
    private static final int NOTE = 0xFFBFBFBF;

    public enum ButtonPosition {
        Right,
        Left,
        Inside
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<ButtonPosition> position = sgGeneral.add(new EnumSetting.Builder<ButtonPosition>()
        .name("button-position")
        .description("Where Scan and Create sit on the shulker screen. Inside tucks them into the title row, which keeps every click within the window.")
        .defaultValue(ButtonPosition.Inside)
        .build()
    );

    private final Setting<Integer> moveDelay = sgGeneral.add(new IntSetting.Builder()
        .name("move-delay")
        .description("Ticks between each click. A move is two clicks, a pick up and a put down, so an item takes roughly twice this. Lower fills faster, but the server is more likely to start correcting the clicks.")
        .defaultValue(4)
        .min(0)
        .max(20)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Boolean> exactLayout = sgGeneral.add(new BoolSetting.Builder()
        .name("exact-layout")
        .description("Make the box match the scan exactly: items already inside are moved into their scanned slots, and anything the scan does not account for is shifted back into your inventory. Off only fills slots that are already empty.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> matchEnchants = sgGeneral.add(new BoolSetting.Builder()
        .name("match-enchantments")
        .description("Only use an item whose enchantments match the one that was scanned. Off means any item of the right type will do.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Report in chat what was scanned, and what was missing after a fill.")
        .defaultValue(true)
        .build()
    );

    // Button geometry, written by the screen mixin each frame and read by the click handler, so the
    // two always agree on where the buttons are.
    private int scanX, scanY, createX, createY;
    private int buttonW = BUTTON_W;
    private int buttonH = BUTTON_H;
    private boolean drawn;
    private int mouseX, mouseY;

    // Click edge detection. The buttons are drawn by hand rather than registered as widgets, so
    // the press is read straight off the window: no screen click handling to hook, and nothing
    // that changes shape between mappings.
    private boolean lastDown;
    private Object lastScreen;

    private boolean filling;
    private int timer;
    private int moved;
    private int[] fillAttempts;
    private int[] evictAttempts;

    // Which slot the stack on the cursor came from, and which slot it is meant for. Both -1 when
    // the cursor is empty.
    private int holdingFrom = -1;
    private int holdingFor = -1;

    public KitCreator() {
        super(JJsTools.CATEGORY, "Kit Creator", "Scan a shulker box, then refill another one the same way. Matches items by type and enchantments, ignoring names.");
    }

    @Override
    public void onDeactivate() {
        filling = false;
        drawn = false;
        lastDown = false;
        lastScreen = null;
        holdingFrom = -1;
        holdingFor = -1;
    }

    // Drawing

    /** Called from the shulker screen every frame. */
    public static void drawButtons(DrawContext context, int x, int y, int width, int height, int mouseX, int mouseY) {
        KitCreator module = active();
        if (module != null) module.draw(context, x, y, width, height, mouseX, mouseY);
    }

    private void draw(DrawContext context, int x, int y, int width, int height, int mouseX, int mouseY) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.textRenderer == null) return;

        this.mouseX = mouseX;
        this.mouseY = mouseY;

        boolean tucked = position.get() == ButtonPosition.Inside;
        buttonW = tucked ? BUTTON_W_INSIDE : BUTTON_W;
        buttonH = tucked ? BUTTON_H_INSIDE : BUTTON_H;

        switch (position.get()) {
            case Right -> {
                scanX = x + width + 4;
                scanY = y + 4;
                createX = scanX;
                createY = scanY + buttonH + BUTTON_GAP;
            }
            case Left -> {
                scanX = x - buttonW - 4;
                scanY = y + 4;
                createX = scanX;
                createY = scanY + buttonH + BUTTON_GAP;
            }
            case Inside -> {
                createX = x + width - buttonW - 7;
                createY = y + 4;
                scanX = createX - buttonW - BUTTON_GAP;
                scanY = createY;
            }
        }

        drawn = true;

        KitStore.Kit kit = KitStore.kit();
        boolean canCreate = kit != null && kit.filledSlots() > 0 && !filling;

        button(context, client.textRenderer, scanX, scanY, filling ? "Cancel" : "Scan", true);
        button(context, client.textRenderer, createX, createY, "Create", canCreate);

        // A one line status under the buttons, so a scanned box reads differently from an empty
        // one without opening the module's settings. Skipped in the title row, where there is
        // nowhere to put it.
        if (!tucked) {
            String note;
            if (filling) note = moved + " moved";
            else if (kit == null || kit.filledSlots() == 0) note = "No kit scanned";
            else note = kit.filledSlots() + " items";

            context.drawText(client.textRenderer, note, scanX, createY + buttonH + 4, NOTE, true);
        }

        pollClick(client, canCreate);
    }

    private void button(DrawContext context, TextRenderer text, int bx, int by, String label, boolean enabled) {
        boolean hovered = enabled && inside(bx, by);

        context.fill(bx, by, bx + buttonW, by + buttonH, enabled ? (hovered ? FACE_HOVER : FACE) : FACE_OFF);
        context.fill(bx, by, bx + buttonW, by + 1, EDGE);
        context.fill(bx, by + buttonH - 1, bx + buttonW, by + buttonH, EDGE);
        context.fill(bx, by, bx + 1, by + buttonH, EDGE);
        context.fill(bx + buttonW - 1, by, bx + buttonW, by + buttonH, EDGE);

        int tx = bx + (buttonW - text.getWidth(label)) / 2;
        int ty = by + (buttonH - 8) / 2;
        context.drawText(text, label, tx, ty, enabled ? TEXT : TEXT_OFF, true);
    }

    private boolean inside(int bx, int by) {
        return mouseX >= bx && mouseX < bx + buttonW && mouseY >= by && mouseY < by + buttonH;
    }

    // Clicking

    /**
     * Reads the left button straight off the window and fires on the press, not the hold.
     *
     * The first frame of a newly opened screen only records the button's state, so a box opened
     * with the mouse still held down does not immediately press a button.
     */
    private void pollClick(MinecraftClient client, boolean canCreate) {
        boolean down = client.getWindow() != null
            && GLFW.glfwGetMouseButton(client.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        boolean fresh = lastScreen != client.currentScreen;
        lastScreen = client.currentScreen;

        boolean pressed = down && !lastDown && !fresh;
        lastDown = down;
        if (!pressed) return;

        if (inside(scanX, scanY)) {
            if (filling) stop("Cancelled");
            else if (mc.player != null && mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler handler) scan(handler);
        }
        else if (inside(createX, createY)) {
            KitStore.Kit kit = KitStore.kit();
            if (kit == null || kit.filledSlots() == 0) {
                if (chatFeedback.get()) MsgUtil.sendModuleMsg("Nothing scanned yet§c..!", this.name);
            }
            else if (canCreate) {
                filling = true;
                timer = 0;
                moved = 0;
                fillAttempts = null;
                evictAttempts = null;
                holdingFrom = -1;
                holdingFor = -1;
            }
        }
    }

    /**
     * Stops a click on one of the buttons reaching the screen underneath.
     *
     * Only matters when the buttons sit outside the window, where vanilla reads a click as "throw
     * what you are holding on the floor". The press itself is handled by the poll above, so if
     * this event never arrives nothing is lost.
     */
    @EventHandler
    private void onMouseClick(MouseClickEvent event) {
        if (!drawn || event.action != KeyAction.Press || !LEFT_CLICK.matches(event.input)) return;
        if (!(mc.currentScreen instanceof ShulkerBoxScreen)) return;

        if (inside(scanX, scanY) || inside(createX, createY)) event.cancel();
    }

    private void scan(ShulkerBoxScreenHandler handler) {
        int boxSize = boxSize(handler);
        if (boxSize <= 0) return;

        KitStore.Kit kit = new KitStore.Kit();
        kit.size = boxSize;
        kit.scanned = System.currentTimeMillis();
        kit.slots = new KitStore.Spec[boxSize];

        for (int i = 0; i < boxSize; i++) {
            kit.slots[i] = KitStore.specOf(handler.getSlot(i).getStack());
        }

        KitStore.set(kit);
        filling = false;

        if (chatFeedback.get()) {
            MsgUtil.sendModuleMsg("Scanned §b" + kit.filledSlots() + "§r items across " + boxSize + " slots§a..!", this.name);
        }
    }

    // Filling

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!filling) return;

        if (mc.player == null || !(mc.currentScreen instanceof ShulkerBoxScreen)
            || !(mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler handler)) {
            stop("Shulker closed");
            return;
        }

        if (timer > 0) {
            timer--;
            return;
        }

        KitStore.Kit kit = KitStore.kit();
        if (kit == null || kit.slots == null) {
            stop("Nothing scanned");
            return;
        }

        int boxSize = boxSize(handler);
        if (boxSize <= 0) {
            stop("Not a shulker");
            return;
        }

        // A backstop. Every pass below is meant to make progress, but a server that keeps correcting
        // clicks can make it look like progress without any, and grinding forever is worse than
        // stopping and saying so.
        if (moved > boxSize * 4 + PLAYER_SLOTS) {
            stop("Gave up after " + moved + " moves");
            return;
        }

        int slots = Math.min(boxSize, kit.slots.length);
        int span = Math.max(boxSize, kit.slots.length);
        if (fillAttempts == null || fillAttempts.length < span) fillAttempts = new int[span];
        if (evictAttempts == null || evictAttempts.length < span) evictAttempts = new int[span];

        boolean exact = exactLayout.get();

        /*
         * Put down whatever is on the cursor before doing anything else.
         *
         * A move is a pick up on one tick and a put down on the next, rather than both in one go.
         * Firing both at once means the second click is built on the client's guess about the
         * first, and when the server disagrees the stack is left stuck to the cursor, which is
         * exactly what the earlier version did. Splitting it means the put down is decided from
         * state the server has already confirmed, and the cursor is checked every tick, so an item
         * can never be left hanging there.
         */
        if (!handler.getCursorStack().isEmpty()) {
            int target = holdingFor;
            int origin = holdingFrom;
            holdingFor = -1;
            holdingFrom = -1;
            timer = moveDelay.get();

            if (target >= 0 && target < boxSize && handler.getSlot(target).getStack().isEmpty()) {
                click(handler, target);
                moved++;
                return;
            }

            // The slot filled up or was never ours: hand it back rather than carry it around.
            int back = origin >= 0 && handler.getSlot(origin).getStack().isEmpty()
                ? origin
                : firstEmptySlot(handler, boxSize);

            if (back >= 0) click(handler, back);
            return;
        }

        holdingFor = -1;
        holdingFrom = -1;

        /*
         * Three passes, re-read from the box every tick.
         *
         * The order matters. Shuffling inside the box first means an item that is already there but
         * in the wrong slot is moved straight to the right one, rather than shifted out to your
         * inventory and dragged back in. Shifting the leftovers out comes last, so nothing is
         * evicted while it could still have been used.
         */

        // 1. Items already in the box, moved to the slot they were scanned in.
        if (exact) {
            for (int i = 0; i < slots; i++) {
                KitStore.Spec spec = kit.slots[i];
                if (spec == null) continue;
                if (!handler.getSlot(i).getStack().isEmpty()) continue;
                if (fillAttempts[i] >= MAX_ATTEMPTS) continue;

                int source = findInBox(handler, kit, slots, boxSize, spec, i);
                if (source < 0) continue;

                pickUp(handler, source, i);
                return;
            }
        }

        // 2. Whatever is missing, pulled out of your own inventory.
        for (int i = 0; i < slots; i++) {
            KitStore.Spec spec = kit.slots[i];
            if (spec == null) continue;
            if (!handler.getSlot(i).getStack().isEmpty()) continue;
            if (fillAttempts[i] >= MAX_ATTEMPTS) continue;

            int source = findInInventory(handler, boxSize, spec);
            if (source < 0) continue;

            pickUp(handler, source, i);
            return;
        }

        /*
         * 3. Anything the scan does not account for, shifted back into your inventory.
         *
         * A slot is only cleared when clearing it achieves something: either the scan says that
         * slot was empty, or the item that belongs there is sitting somewhere this can fetch it
         * from. Without that test a kit scanned from a different box, or a matching rule that does
         * not recognise an item, would strip the box bare and put nothing back.
         */
        if (exact) {
            for (int i = 0; i < boxSize; i++) {
                ItemStack stack = handler.getSlot(i).getStack();
                if (stack.isEmpty()) continue;
                if (isCorrect(handler, kit, slots, i)) continue;
                if (evictAttempts[i] >= MAX_ATTEMPTS) continue;

                KitStore.Spec spec = i < slots ? kit.slots[i] : null;
                if (spec != null
                    && findInBox(handler, kit, slots, boxSize, spec, i) < 0
                    && findInInventory(handler, boxSize, spec) < 0) continue;

                if (!hasFreeInventorySlot(handler, boxSize)) continue;

                evictAttempts[i]++;
                InvUtils.shiftClick().slotId(i);
                timer = moveDelay.get();
                moved++;
                return;
            }
        }

        // Nothing movable left: either the box matches the kit or we are short of items. Counted
        // from the box itself rather than from how many clicks were sent, since a click the server
        // corrected is not an item in a slot.
        int filled = 0;
        int missing = 0;
        int extra = 0;
        for (int i = 0; i < boxSize; i++) {
            KitStore.Spec spec = i < slots ? kit.slots[i] : null;
            boolean empty = handler.getSlot(i).getStack().isEmpty();

            if (spec == null) {
                if (!empty) extra++;
            }
            else if (empty) missing++;
            else filled++;
        }

        filling = false;

        if (chatFeedback.get()) {
            String msg = "Filled §b" + filled + "§r of " + (filled + missing) + " slots";
            if (missing > 0) msg += ", §e" + missing + "§r left empty";
            if (extra > 0) msg += ", §e" + extra + "§r could not be cleared";
            MsgUtil.sendModuleMsg(msg + "§a..!", this.name);
        }
    }

    /** True if this box slot already holds what the scan says belongs in it. */
    private boolean isCorrect(ShulkerBoxScreenHandler handler, KitStore.Kit kit, int slots, int slot) {
        if (slot >= slots) return false;

        KitStore.Spec spec = kit.slots[slot];
        if (spec == null) return false;

        return KitStore.matches(spec, handler.getSlot(slot).getStack(), matchEnchants.get());
    }

    /**
     * A box slot holding an item for this spec, or -1.
     *
     * Slots that already hold the right thing are never used as a source, which is what stops two
     * slots wanting the same item from passing it back and forth forever.
     */
    private int findInBox(ShulkerBoxScreenHandler handler, KitStore.Kit kit, int slots, int boxSize,
                          KitStore.Spec spec, int target) {
        for (int i = 0; i < boxSize; i++) {
            if (i == target) continue;
            if (isCorrect(handler, kit, slots, i)) continue;
            if (KitStore.matches(spec, handler.getSlot(i).getStack(), matchEnchants.get())) return i;
        }

        return -1;
    }

    /** Picks a stack up, noting where it came from and where it is meant to end up. */
    private void pickUp(ShulkerBoxScreenHandler handler, int source, int target) {
        fillAttempts[target]++;
        holdingFrom = source;
        holdingFor = target;
        timer = moveDelay.get();
        click(handler, source);
    }

    /** A plain left click on a slot, the same action a player makes by hand. */
    private void click(ShulkerBoxScreenHandler handler, int slot) {
        if (mc.interactionManager == null || mc.player == null) return;
        mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
    }

    /** Any empty slot, your inventory first, for handing back a stack that has nowhere to go. */
    private int firstEmptySlot(ShulkerBoxScreenHandler handler, int boxSize) {
        for (int i = boxSize; i < boxSize + PLAYER_SLOTS && i < handler.slots.size(); i++) {
            if (handler.getSlot(i).getStack().isEmpty()) return i;
        }

        for (int i = 0; i < boxSize; i++) {
            if (handler.getSlot(i).getStack().isEmpty()) return i;
        }

        return -1;
    }

    /**
     * True if there is an empty slot in your inventory to shift something into.
     *
     * Only empty slots count. An item that would merge into a part used stack is turned down here,
     * which costs a wasted eviction now and then but never fires a click that does nothing.
     */
    private boolean hasFreeInventorySlot(ShulkerBoxScreenHandler handler, int boxSize) {
        for (int i = boxSize; i < boxSize + PLAYER_SLOTS && i < handler.slots.size(); i++) {
            if (handler.getSlot(i).getStack().isEmpty()) return true;
        }

        return false;
    }

    /** The inventory slot holding an item for this spec, or -1. */
    private int findInInventory(ShulkerBoxScreenHandler handler, int boxSize, KitStore.Spec spec) {
        int best = -1;
        int bestCount = Integer.MAX_VALUE;

        for (int i = boxSize; i < boxSize + PLAYER_SLOTS && i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (!KitStore.matches(spec, stack, matchEnchants.get())) continue;

            // Smallest stack that still covers what the slot held, so a scan of one block does not
            // empty a full stack into it when a loose few are sitting in your inventory.
            int count = stack.getCount();
            if (count >= spec.count && count < bestCount) {
                best = i;
                bestCount = count;
            }
            else if (best == -1) {
                best = i;
            }
        }

        return best;
    }

    private int boxSize(ShulkerBoxScreenHandler handler) {
        return handler.slots.size() - PLAYER_SLOTS;
    }

    private void stop(String why) {
        filling = false;
        holdingFrom = -1;
        holdingFor = -1;
        if (chatFeedback.get()) MsgUtil.sendModuleMsg(why + "§c..!", this.name);
    }

    // Read by the shulker screen mixin

    private static KitCreator active() {
        Modules modules = Modules.get();
        if (modules == null) return null;

        KitCreator module = modules.get(KitCreator.class);
        return module != null && module.isActive() ? module : null;
    }

    public static boolean on() {
        return active() != null;
    }
}
