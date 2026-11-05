package dev.jjstools.util;

import io.netty.util.internal.ThreadLocalRandom;

import java.time.Instant;
import net.minecraft.util.Hand;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.*;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.world.Dimension;
import dev.jjstools.mixin.accessor.ClientConnectionAccessor;
import static meteordevelopment.meteorclient.MeteorClient.mc;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import net.minecraft.network.encryption.NetworkEncryptionUtils;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.network.packet.c2s.common.ClientOptionsC2SPacket;
import meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect;
import meteordevelopment.meteorclient.mixin.ClientPlayNetworkHandlerAccessor;

/**
 * Shared helpers for the ported modules.
 *
 * @author Tas [@0xTas] <root@0xTas.dev> (original StardustUtil)
 */
public class JJUtil {
    /** Xaero map integration is not included, so waypoint options stay hidden. */
    public static final boolean XAERO_AVAILABLE = false;

    public enum TextColor {
        Black("§0"), White("§f"), Gray("§8"), Light_Gray("§7"),
        Dark_Green("§2"), Green("§a"), Dark_Aqua("§3"), Aqua("§b"),
        Dark_Blue("§1"), Blue("§9"), Dark_Red("§4"), Red("§c"),
        Dark_Purple("§5"), Purple("§d"), Gold("§6"), Yellow("§e"),
        Random("");

        public final String label;

        TextColor(String label) {
            this.label = label;
        }
    }

    /** A random chat colour code, never black or either grey, for accents. */
    public static String rCC() {
        String color = "§7";
        TextColor[] colors = TextColor.values();

        // Leave out grey, light grey and black, which do not stand out as accents.
        while (color.equals("§0") || color.equals("§8") || color.equals("§7")) {
            int luckyIndex = ThreadLocalRandom.current().nextInt(colors.length);
            color = colors[luckyIndex].label;
        }

        return color;
    }

    public static boolean isIn2b2tQueue() {
        if (mc.player == null || mc.getNetworkHandler() == null) return false;

        return PlayerUtils.getDimension().equals(Dimension.End)
            && mc.player.getAbilities().allowFlying && mc.getNetworkHandler().getPlayerList().size() <= 1;
    }

    public enum IllegalDisconnectMethod {
        Slot, Chat, Interact, Movement, SequenceBreak, InvalidSettings
    }

    public static void illegalDisconnect(boolean disableAutoReconnect, IllegalDisconnectMethod illegalDisconnectMethod) {
        if (!Utils.canUpdate()) return;
        if (disableAutoReconnect) disableAutoReconnect();

        Packet<?> illegalPacket = null;
        switch (illegalDisconnectMethod) {
            case Slot -> illegalPacket = new UpdateSelectedSlotC2SPacket(-69);
            case Chat -> illegalPacket = new ChatMessageC2SPacket(
                "§",
                Instant.now(),
                NetworkEncryptionUtils.SecureRandomUtil.nextLong(),
                null,
                ((ClientPlayNetworkHandlerAccessor) mc.getNetworkHandler()).meteor$getLastSeenMessagesCollector().collect().update()
            );
            case Interact -> illegalPacket = PlayerInteractEntityC2SPacket.interact(mc.player, false, Hand.MAIN_HAND);
            case Movement -> illegalPacket = new PlayerMoveC2SPacket.PositionAndOnGround(Double.NaN, 69, Double.NaN, false, false);
            case SequenceBreak -> illegalPacket = new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, -420, 13.37F, 69.69F);
            case InvalidSettings -> illegalPacket = new ClientOptionsC2SPacket(new SyncedClientOptions(
                mc.options.language, -69,
                mc.options.getChatVisibility().getValue(), mc.options.getChatColors().getValue(),
                mc.options.getSyncedOptions().playerModelParts(), mc.options.getMainArm().getValue(),
                mc.options.getSyncedOptions().filtersText(), mc.options.getAllowServerListing().getValue(),
                mc.options.getSyncedOptions().particleStatus()
            ));
        }
        if (illegalPacket != null) ((ClientConnectionAccessor) mc.getNetworkHandler().getConnection()).invokeSendImmediately(
            illegalPacket, null, true
        );
    }

    /** Turns Meteor's Auto Reconnect off, so a deliberate disconnect is not undone a moment later. */
    public static void disableAutoReconnect() {
        Modules mods = Modules.get();
        if (mods == null) return;
        AutoReconnect atrc = mods.get(AutoReconnect.class);
        if (atrc.isActive()) atrc.toggle();
    }
}
