package dev.jjstools.mixin;

import dev.jjstools.modules.PickupLootStream;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.network.packet.s2c.play.ItemPickupAnimationS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells Pickup Loot Stream what you picked up.
 *
 * The server sends this when an item flies into someone's inventory. It arrives first on the
 * network thread, where vanilla hands it over to the game's thread and stops, so only the run on
 * the game's thread is used: there the item is still in the world to be read, because vanilla
 * only takes it away further down this same method.
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerPickupMixin {
    @Inject(method = "onItemPickupAnimation", at = @At("HEAD"))
    private void jjsTools$pickedUp(ItemPickupAnimationS2CPacket packet, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!mc.isOnThread() || mc.world == null || mc.player == null) return;
        if (packet.getCollectorEntityId() != mc.player.getId()) return;

        Entity entity = mc.world.getEntityById(packet.getEntityId());
        if (entity instanceof ItemEntity item) {
            PickupLootStream.onPickup(item.getStack(), packet.getStackAmount());
            dev.jjstools.panel.SessionStats.pickedUp(packet.getStackAmount());
        }
    }
}
