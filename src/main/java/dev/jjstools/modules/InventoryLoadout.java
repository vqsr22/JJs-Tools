package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import dev.jjstools.util.LoadoutStore;
import dev.jjstools.util.MsgUtil;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

/**
 * Puts your inventory back the way you had it.
 *
 * One key writes down where everything is sitting, another key shuffles it back into place. Useful
 * after a death, after emptying a shulker into a mess, or any time a trip has left your hotbar in
 * the wrong order and you want it back without dragging thirty items by hand.
 *
 * Matching ignores names and almost every enchantment. The exceptions are Silk Touch and Fortune,
 * because those decide what a tool is for: a silk pick cannot stand in for a fortune one. Anything
 * else is treated as the same item, so a worn Unbreaking II pick happily takes the slot the
 * Unbreaking III one was in.
 *
 * Everything here happens inside one container, so a move is usually a swap rather than a fill,
 * and the third click is what puts the displaced item where the first one came from.
 */
public class InventoryLoadout extends Module {
    /** The player screen handler's own numbering. */
    private static final int ARMOUR = 5;     // helmet, then chest, legs, boots
    private static final int MAIN = 9;       // top left of the three main rows
    private static final int LAST_MAIN = 44; // bottom right of the hotbar
    private static final int OFFHAND = 45;

    private static final int SLOTS = OFFHAND - ARMOUR + 1;
    private static final int MAX_ATTEMPTS = 3;

    private enum Phase {
        Idle,
        Placing,
        Returning
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Keybind> saveKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("save")
        .description("Writes down where everything in your inventory is sitting right now.")
        .defaultValue(Keybind.none())
        .action(this::onSaveKey)
        .build()
    );

    private final Setting<Keybind> applyKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("apply")
        .description("Shuffles what you are carrying back into the saved slots.")
        .defaultValue(Keybind.none())
        .action(this::onApplyKey)
        .build()
    );

    private final Setting<Integer> moveDelay = sgGeneral.add(new IntSetting.Builder()
        .name("move-delay")
        .description("Ticks between clicks. A swap is three clicks, so an item takes roughly three times this.")
        .defaultValue(4)
        .min(0)
        .max(20)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Boolean> includeArmour = sgGeneral.add(new BoolSetting.Builder()
        .name("include-armour")
        .description("Save and restore what you are wearing. An armour slot only takes the piece that belongs in it, so nothing else can end up there.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> includeOffhand = sgGeneral.add(new BoolSetting.Builder()
        .name("include-offhand")
        .description("Save and restore the offhand along with the rest.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Report what was saved, and how it went when applying.")
        .defaultValue(true)
        .build()
    );

    private Phase phase = Phase.Idle;
    private int moveTo = -1;
    private int moveFrom = -1;
    private int timer = 0;
    private int moved = 0;
    private boolean applying = false;
    private int[] attempts = new int[SLOTS];

    public InventoryLoadout() {
        super(JJsTools.CATEGORY, "inventory-loadout", "Save where everything in your inventory sits, and put it back on a key.");
    }

    @Override
    public void onDeactivate() {
        stop(null);
    }

    // Keys

    private void onSaveKey() {
        if (!isActive() || !usable()) return;

        PlayerScreenHandler handler = mc.player.playerScreenHandler;

        LoadoutStore.Loadout loadout = new LoadoutStore.Loadout();
        loadout.saved = System.currentTimeMillis();
        loadout.firstSlot = first();
        loadout.slots = new LoadoutStore.Spec[last() - first() + 1];

        for (int slot = first(); slot <= last(); slot++) {
            loadout.slots[slot - first()] = LoadoutStore.specOf(handler.getSlot(slot).getStack());
        }

        LoadoutStore.set(loadout);
        stop(null);

        if (chatFeedback.get()) {
            MsgUtil.sendModuleMsg("Saved §b" + loadout.filledSlots() + "§r items§a..!", this.name);
        }
    }

    private void onApplyKey() {
        if (!isActive() || !usable()) return;

        LoadoutStore.Loadout loadout = LoadoutStore.loadout();
        if (loadout == null || loadout.filledSlots() == 0) {
            if (chatFeedback.get()) MsgUtil.sendModuleMsg("Nothing saved yet§c..!", this.name);
            return;
        }

        applying = true;
        phase = Phase.Idle;
        moveTo = -1;
        moveFrom = -1;
        timer = 0;
        moved = 0;
        attempts = new int[SLOTS];
    }

    /**
     * True when the player's own inventory is the thing being clicked.
     *
     * With a chest or anvil open the slot numbers mean something else entirely, and a click sent
     * against the wrong handler moves whichever item happens to be sitting at that number.
     */
    private boolean usable() {
        return mc.player != null
            && mc.interactionManager != null
            && mc.player.currentScreenHandler == mc.player.playerScreenHandler;
    }

    private int first() {
        return includeArmour.get() ? ARMOUR : MAIN;
    }

    private int last() {
        return includeOffhand.get() ? OFFHAND : LAST_MAIN;
    }

    /**
     * What the save says belongs in this slot, or null.
     *
     * Looked up by slot number rather than by array position, so turning armour on or off does
     * not shift everything else along by four.
     */
    private LoadoutStore.Spec specFor(LoadoutStore.Loadout loadout, int slot) {
        int index = slot - loadout.firstSlot();
        if (index < 0 || index >= loadout.slots.length) return null;

        return loadout.slots[index];
    }

    // Applying

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!applying) return;

        if (!usable()) {
            stop("Inventory is not what is open");
            return;
        }

        if (timer > 0) {
            timer--;
            return;
        }

        PlayerScreenHandler handler = mc.player.playerScreenHandler;

        /*
         * Whatever is on the cursor is dealt with before anything new is started.
         *
         * A move is one click per tick rather than all of them at once. Firing them together means
         * each click is built on the client's guess about the last, and when the server disagrees
         * the stack is left stuck to the cursor. One at a time, every click is decided from state
         * the server has already confirmed.
         */
        switch (phase) {
            case Placing -> {
                if (handler.getCursorStack().isEmpty()) {
                    phase = Phase.Idle;
                    return;
                }

                // Occupied means this is a swap, and the item already there comes back on the
                // cursor and needs somewhere to go.
                boolean swap = !handler.getSlot(moveTo).getStack().isEmpty();

                click(handler, moveTo);
                moved++;
                timer = moveDelay.get();
                phase = swap ? Phase.Returning : Phase.Idle;
                return;
            }

            case Returning -> {
                if (handler.getCursorStack().isEmpty()) {
                    phase = Phase.Idle;
                    return;
                }

                click(handler, moveFrom);
                timer = moveDelay.get();
                phase = Phase.Idle;
                return;
            }

            default -> {
                // An item on the cursor with nothing in flight is not ours to carry around.
                if (!handler.getCursorStack().isEmpty()) {
                    int free = firstEmpty(handler);
                    if (free >= 0) {
                        click(handler, free);
                        timer = moveDelay.get();
                    }
                    return;
                }
            }
        }

        LoadoutStore.Loadout loadout = LoadoutStore.loadout();
        if (loadout == null || loadout.slots == null) {
            stop("Nothing saved");
            return;
        }

        for (int slot = first(); slot <= last(); slot++) {
            int index = slot - ARMOUR;

            LoadoutStore.Spec spec = specFor(loadout, slot);
            if (spec == null) continue;
            if (LoadoutStore.matches(spec, handler.getSlot(slot).getStack())) continue;
            if (attempts[index] >= MAX_ATTEMPTS) continue;

            int source = findSource(handler, loadout, spec, slot);
            if (source < 0) continue;

            attempts[index]++;
            moveTo = slot;
            moveFrom = source;
            phase = Phase.Placing;
            timer = moveDelay.get();
            click(handler, source);
            return;
        }

        finish(handler, loadout);
    }

    /**
     * A slot holding something that belongs in the target, or -1.
     *
     * A slot already holding what it is supposed to is never taken from, which is what stops two
     * slots wanting the same kind of item from trading it back and forth forever.
     */
    private int findSource(PlayerScreenHandler handler, LoadoutStore.Loadout loadout,
                           LoadoutStore.Spec spec, int target) {
        for (int slot = first(); slot <= last(); slot++) {
            if (slot == target) continue;

            LoadoutStore.Spec here = specFor(loadout, slot);
            ItemStack stack = handler.getSlot(slot).getStack();

            if (here != null && LoadoutStore.matches(here, stack)) continue;
            if (LoadoutStore.matches(spec, stack)) return slot;
        }

        return -1;
    }

    private int firstEmpty(PlayerScreenHandler handler) {
        // Only ordinary slots. An armour slot refuses anything that is not armour for it, so a
        // click there would leave the item stuck on the cursor rather than putting it down.
        for (int slot = MAIN; slot <= LAST_MAIN; slot++) {
            if (handler.getSlot(slot).getStack().isEmpty()) return slot;
        }

        return -1;
    }

    private void click(PlayerScreenHandler handler, int slot) {
        if (mc.interactionManager == null || mc.player == null) return;
        mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
    }

    private void finish(PlayerScreenHandler handler, LoadoutStore.Loadout loadout) {
        int right = 0;
        int wrong = 0;

        for (int slot = first(); slot <= last(); slot++) {
            LoadoutStore.Spec spec = specFor(loadout, slot);
            if (spec == null) continue;

            if (LoadoutStore.matches(spec, handler.getSlot(slot).getStack())) right++;
            else wrong++;
        }

        applying = false;
        phase = Phase.Idle;

        if (chatFeedback.get()) {
            String msg = "Put §b" + right + "§r of " + (right + wrong) + " items back";
            if (wrong > 0) msg += ", §e" + wrong + "§r missing";
            MsgUtil.sendModuleMsg(msg + "§a..!", this.name);
        }
    }

    private void stop(String why) {
        applying = false;
        phase = Phase.Idle;
        moveTo = -1;
        moveFrom = -1;
        timer = 0;

        if (why != null && chatFeedback.get()) MsgUtil.sendModuleMsg(why + "§c..!", this.name);
    }
}
