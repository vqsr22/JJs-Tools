package dev.jjstools.panel;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.entity.player.BreakBlockEvent;
import meteordevelopment.meteorclient.events.entity.player.PlaceBlockEvent;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
//#if MC >= 1.21.9
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
//#else
//$$ import meteordevelopment.meteorclient.events.meteor.MouseButtonEvent;
//#endif
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.render.color.Color;
import dev.jjstools.modules.ControlPanel;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.DeathMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the Control Panel's records up to date whether or not the panel is open.
 *
 * Whispers, deaths, session numbers, the blocked list and stash containers all have to be caught
 * as they happen, so this listens all the time from the moment the game starts. It does very
 * little each tick: reading a chat line, adding a number, saving a file a few seconds after a
 * change.
 */
public final class PanelData {
    private static final PanelData INSTANCE = new PanelData();

    /** Containers to outline in the world, set by the stash manager. */
    public static final List<BlockPos> highlighted = new ArrayList<>();
    private static final Color SIDE = new Color(80, 255, 120, 40);
    private static final Color LINE = new Color(80, 255, 120, 220);
    private static final Color TARGET_SIDE = new Color(255, 220, 60, 50);
    private static final Color TARGET_LINE = new Color(255, 220, 60, 255);

    private PanelData() {}

    /** The box around a container, both halves of it for a double chest. */
    private static Box wholeContainer(MinecraftClient mc, BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.contains(ChestBlock.CHEST_TYPE) && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
            BlockPos other = pos.offset(ChestBlock.getFacing(state));
            return new Box(Math.min(pos.getX(), other.getX()), pos.getY(), Math.min(pos.getZ(), other.getZ()),
                Math.max(pos.getX(), other.getX()) + 1, pos.getY() + 1, Math.max(pos.getZ(), other.getZ()) + 1);
        }
        return new Box(pos);
    }

    public static void init() {
        MeteorClient.EVENT_BUS.subscribe(INSTANCE);
    }

    /** A container broken where you can see it leaves the stash manager. */
    @EventHandler
    private void onBlockUpdate(meteordevelopment.meteorclient.events.world.BlockUpdateEvent event) {
        StashStore.onBlockChanged(event.pos, event.oldState, event.newState);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        dev.jjstools.gui.jjtheme.JJGuiTheme.tick();
        dev.jjstools.gui.MeteorFonts.tick();
        ChatLog.tick();
        SessionStats.tick();
        StashStore.tick();
        StashWalker.tick();
        BlockedList.tick();
        dev.jjstools.util.AddonIcon.tick();
    }

    /** The Open GUI Key: opens the panel from the game, and closes it unless you are typing in it. */
    @EventHandler
    private void onKey(KeyEvent event) {
        if (event.action != KeyAction.Press) return;
        Keybind bind = ControlPanel.openKey();
        if (bind == null || !bind.isSet() || !dev.jjstools.compat.Compat.matches(bind, event)) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen == null && mc.player != null) {
            mc.setScreen(new ControlPanelScreen());
            event.cancel();
        } else if (mc.currentScreen instanceof ControlPanelScreen screen && !screen.typing()) {
            screen.close();
            event.cancel();
        }
    }

    @EventHandler
    //#if MC >= 1.21.9
    private void onMouse(MouseClickEvent event) {
    //#else
    //$$ private void onMouse(MouseButtonEvent event) {
    //#endif
        if (event.action != KeyAction.Press) return;
        Keybind bind = ControlPanel.openKey();
        if (bind == null || !bind.isSet() || !dev.jjstools.compat.Compat.matches(bind, event)) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen == null && mc.player != null) {
            mc.setScreen(new ControlPanelScreen());
            event.cancel();
        }
    }

    @EventHandler
    private void onJoin(GameJoinedEvent event) {
        SessionStats.onJoin();
    }

    @EventHandler
    private void onLeave(GameLeftEvent event) {
        SessionStats.onLeave();
        ChatLog.save();
        StashStore.saveAll();
        StashWalker.stop();
        highlighted.clear();
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        if (event.getMessage() == null) return;
        String line = event.getMessage().getString();
        ChatLog.onChat(line);
        // The pages of a refresh from the panel are read here and kept out of chat.
        if (BlockedList.onChat(event.getMessage())) event.cancel();
    }

    @EventHandler
    private void onSend(SendMessageEvent event) {
        SessionStats.chatSent();
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (event.packet instanceof DeathMessageS2CPacket death) {
            // Handled on the game's thread, where the player and the world can be read safely.
            mc.execute(() -> {
                if (mc.player != null && death.playerId() == mc.player.getId()) DeathLog.record(death.message());
            });
        }
        else if (event.packet instanceof EntityStatusS2CPacket status && status.getStatus() == 35) {
            mc.execute(() -> {
                if (mc.world == null || mc.player == null) return;
                if (status.getEntity(mc.world) == mc.player) SessionStats.totem();
            });
        }
    }

    @EventHandler
    private void onPlace(PlaceBlockEvent event) {
        SessionStats.placed();
    }

    @EventHandler
    private void onBreak(BreakBlockEvent event) {
        SessionStats.broken();
    }

    @EventHandler
    private void onEntity(EntityAddedEvent event) {
        Entity entity = event.entity;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (entity instanceof PlayerEntity player && player != mc.player) SessionStats.sawPlayer(dev.jjstools.compat.Compat.name(player.getGameProfile()));
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || !ControlPanel.stashOutlines()) return;

        for (BlockPos pos : highlighted) {
            event.renderer.box(wholeContainer(mc, pos), SIDE, LINE, ShapeMode.Both, 0);
        }
        BlockPos target = StashWalker.target();
        if (target != null) event.renderer.box(target, TARGET_SIDE, TARGET_LINE, ShapeMode.Both, 0);
    }
}
