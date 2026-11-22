package dev.jjstools.mixin;

import dev.jjstools.modules.NoPacketKicks;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every inventory click goes through here, yours and every module's. At the inventory packet
 * limit it is stopped before the game moves anything, so the item stays put on your screen and
 * the server is never asked: nothing is left out of step, and no packet goes over the limit.
 */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class PacketLimitClickMixin {
    @Inject(method = "clickSlot", at = @At("HEAD"), cancellable = true)
    private void jjsTools$packetLimit(int syncId, int slotId, int button, SlotActionType actionType, PlayerEntity player, CallbackInfo ci) {
        if (!NoPacketKicks.allowInventory()) ci.cancel();
    }
}
