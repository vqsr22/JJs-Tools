package dev.jjstools.util;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
//#if MC >= 1.21.5
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
//#endif
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
//#if MC >= 1.21.5
import net.minecraft.screen.sync.ComponentChangesHash;
import net.minecraft.screen.sync.ItemStackHash;

import static meteordevelopment.meteorclient.MeteorClient.mc;
//#endif

/**
 * Builds a ClickSlotC2SPacket from the pre-1.21.5 argument list.
 *
 * Since 1.21.5 the packet carries hashes of item stacks instead of the stacks
 * themselves, and it is a record whose argument order is
 * (syncId, revision, slot, button, actionType, modifiedStacks, cursor).
 * This keeps the old call sites unchanged: same order as the old constructor.
 */
public final class ClickPackets {
    private ClickPackets() {
    }

    public static ClickSlotC2SPacket of(int syncId, int revision, int slot, int button, SlotActionType actionType,
                                        ItemStack cursor, Int2ObjectMap<ItemStack> modifiedStacks) {
        //#if MC >= 1.21.5
        //#if MC >= 1.21.6
        ComponentChangesHash.ComponentHasher hasher = mc.getNetworkHandler().getComponentHasher();
        //#else
        //$$ // Not given a name in the 1.21.5 mappings.
        //$$ ComponentChangesHash.ComponentHasher hasher = mc.getNetworkHandler().method_68823();
        //#endif

        Int2ObjectMap<ItemStackHash> hashed = new Int2ObjectOpenHashMap<>();
        for (Int2ObjectMap.Entry<ItemStack> entry : modifiedStacks.int2ObjectEntrySet()) {
            hashed.put(entry.getIntKey(), ItemStackHash.fromItemStack(entry.getValue(), hasher));
        }

        return new ClickSlotC2SPacket(syncId, revision, (short) slot, (byte) button, actionType, hashed,
            ItemStackHash.fromItemStack(cursor, hasher));
        //#else
        //$$ return new ClickSlotC2SPacket(syncId, revision, slot, button, actionType, cursor, modifiedStacks);
        //#endif
    }
}
