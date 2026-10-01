package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import dev.jjstools.util.BedMarkerStore;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BedBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.Locale;

/**
 * Marks the bed you last set your spawn at.
 *
 * REQUIRES CHAT. The server never tells the client where your bed spawn is, so the only way to
 * know is to watch for the "Respawn point set" message after you click a bed. If that message is
 * hidden, filtered, reworded by the server, or you have chat off, this cannot work.
 *
 * The position and dimension are kept in .minecraft/jjs-tools/bed-marker.json and survive updates.
 * Setting a new spawn replaces the old marker, same as the game does.
 *
 * If the bed is later broken the server silently moves your spawn and says nothing, so the marker
 * is left alone rather than guessing.
 */
public class BedMarker extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> chatMessage = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-message")
        .description("Say the coordinates in chat when a new spawn is recorded.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> sideColour = sgGeneral.add(new ColorSetting.Builder()
        .name("side-color")
        .defaultValue(new SettingColor(150, 60, 255, 60))
        .build()
    );

    private final Setting<SettingColor> lineColour = sgGeneral.add(new ColorSetting.Builder()
        .name("line-color")
        .defaultValue(new SettingColor(150, 60, 255, 230))
        .build()
    );

    /**
     * The last bed you clicked. Held until the server confirms, because clicking a bed does not
     * always set a spawn: it may be day, the bed may be occupied, or it may just put you to sleep.
     */
    private BlockPos pendingBed;
    private long pendingAt;

    /** How long a click stays pending. The confirmation comes back in the same second or not at all. */
    private static final long PENDING_MS = 3000;

    public BedMarker() {
        super(JJsTools.CATEGORY, "bed-marker", "Marks the bed you last set your spawn at. Needs chat visible to see the \"Respawn point set\" message.");
    }

    @Override
    public void onActivate() {
        BedMarkerStore.load();
        pendingBed = null;
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (!(event.packet instanceof PlayerInteractBlockC2SPacket packet)) return;
        if (mc.world == null) return;

        BlockPos pos = packet.getBlockHitResult().getBlockPos();
        if (!(mc.world.getBlockState(pos).getBlock() instanceof BedBlock)) return;

        pendingBed = pos;
        pendingAt = System.currentTimeMillis();
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        if (pendingBed == null || mc.world == null) return;
        if (System.currentTimeMillis() - pendingAt > PENDING_MS) {
            pendingBed = null;
            return;
        }

        String text = event.getMessage().getString().toLowerCase(Locale.ROOT);
        // Matches vanilla's "Respawn point set". Kept loose so a reworded server message still
        // has a chance of matching.
        if (!text.contains("respawn point set")) return;

        BlockPos bed = pendingBed;
        pendingBed = null;

        String dimension = mc.world.getRegistryKey().getValue().getPath();
        BedMarkerStore.set(bed, dimension);

        if (chatMessage.get()) {
            info(Text.literal("Spawn set at %d, %d, %d in %s"
                .formatted(bed.getX(), bed.getY(), bed.getZ(), dimension)));
        }
    }

    /**
     * Clears the marker when the bed is broken in front of you.
     *
     * Only acts on a block update we actually receive, which means the chunk is loaded and you
     * were there to see it. Out of render distance the server says nothing, so the marker is left
     * alone rather than guessed at.
     */
    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        BedMarkerStore.Marker marker = BedMarkerStore.get();
        if (marker == null || mc.world == null) return;

        String here = mc.world.getRegistryKey().getValue().getPath();
        if (!here.equals(marker.dimension)) return;

        BlockPos pos = marker.pos();
        if (!event.pos.equals(pos) && !event.pos.equals(otherHalf(pos))) return;

        // Still a bed? Then it was something else changing, like the occupied state.
        if (event.newState.getBlock() instanceof BedBlock) return;

        BedMarkerStore.clear();
        if (chatMessage.get()) info(Text.literal("Your bed was broken, marker cleared."));
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        BedMarkerStore.Marker marker = BedMarkerStore.get();
        if (marker == null || mc.world == null) return;

        // Overworld coordinates drawn while you are in the Nether would be wrong by a factor of
        // eight, so the box only appears in the dimension the bed is actually in.
        String here = mc.world.getRegistryKey().getValue().getPath();
        if (!here.equals(marker.dimension)) return;

        BlockPos pos = marker.pos();
        Box box = new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);

        // A bed is two blocks. Draw the other half too, so the whole bed is highlighted rather
        // than just the pillow end.
        BlockPos other = otherHalf(pos);
        if (other != null) {
            box = box.union(new Box(other.getX(), other.getY(), other.getZ(),
                other.getX() + 1, other.getY() + 1, other.getZ() + 1));
        }

        event.renderer.box(box, sideColour.get(), lineColour.get(), ShapeMode.Both, 0);
    }

    /**
     * The other half of the bed, taken from the block's own facing and part so it is right however
     * the bed is turned. Null if the bed is gone, in which case only the stored position is drawn.
     */
    private BlockPos otherHalf(BlockPos pos) {
        var state = mc.world.getBlockState(pos);
        if (!(state.getBlock() instanceof BedBlock)) return null;

        var part = state.get(BedBlock.PART);
        var facing = state.get(BedBlock.FACING);

        return part == BedPart.HEAD ? pos.offset(facing.getOpposite()) : pos.offset(facing);
    }

    @Override
    public String getInfoString() {
        BedMarkerStore.Marker marker = BedMarkerStore.get();
        return marker == null ? null : "%d, %d, %d".formatted(marker.x, marker.y, marker.z);
    }
}
