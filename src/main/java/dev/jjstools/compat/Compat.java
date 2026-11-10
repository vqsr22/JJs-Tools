package dev.jjstools.compat;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

/**
 * Small calls whose names differ between the Minecraft versions JJ's Tools builds for, in one
 * place so the rest of the code can stay the same. See gradle/versioned.gradle for the //#if blocks.
 */
public final class Compat {
    private Compat() {}

    /** Where an entity is. */
    public static Vec3d pos(Entity entity) {
        //#if MC >= 1.21.9
        return entity.getEntityPos();
        //#else
        //$$ return entity.getPos();
        //#endif
    }

    /** Whether a key event is the given bind. */
    public static boolean matches(meteordevelopment.meteorclient.utils.misc.Keybind bind, meteordevelopment.meteorclient.events.meteor.KeyEvent event) {
        //#if MC >= 1.21.9
        return bind.matches(event.input);
        //#else
        //$$ return bind.matches(true, event.key, event.modifiers);
        //#endif
    }

    /** Whether a mouse button event is the given bind. */
    //#if MC >= 1.21.9
    public static boolean matches(meteordevelopment.meteorclient.utils.misc.Keybind bind, meteordevelopment.meteorclient.events.meteor.MouseClickEvent event) {
        return bind.matches(event.input);
    }
    //#else
    //$$ public static boolean matches(meteordevelopment.meteorclient.utils.misc.Keybind bind, meteordevelopment.meteorclient.events.meteor.MouseButtonEvent event) {
    //$$     return bind.matches(false, event.button, 0);
    //$$ }
    //#endif

    /** Where a teleport packet puts an entity, its relative parts added to where it is now. */
    public static Vec3d teleportTarget(Entity entity, net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket packet) {
        //#if MC >= 1.21.9
        return net.minecraft.entity.EntityPosition.apply(net.minecraft.entity.EntityPosition.fromEntity(entity), packet.change(), packet.relatives()).position();
        //#else
        //$$ return net.minecraft.entity.player.PlayerPosition.apply(net.minecraft.entity.player.PlayerPosition.fromEntity(entity), packet.change(), packet.relatives()).position();
        //#endif
    }

    /** A text style drawn in the font with the given id. */
    public static net.minecraft.text.Style withFont(net.minecraft.text.Style style, net.minecraft.util.Identifier font) {
        //#if MC >= 1.21.9
        return style.withFont(new net.minecraft.text.StyleSpriteSource.Font(font));
        //#else
        //$$ return style.withFont(font);
        //#endif
    }

    /** A Meteor font's file, read into a direct buffer for stb_truetype. */
    public static java.nio.ByteBuffer fontBytes(meteordevelopment.meteorclient.renderer.text.FontFace face) throws java.io.IOException {
        //#if MC >= 1.21.9
        return face.readToDirectByteBuffer();
        //#else
        //$$ byte[] bytes = meteordevelopment.meteorclient.utils.Utils.readBytes(face.toStream());
        //$$ return org.lwjgl.BufferUtils.createByteBuffer(bytes.length).put(bytes).flip();
        //#endif
    }

    /** A profile's player name. */
    public static String name(GameProfile profile) {
        //#if MC >= 1.21.9
        return profile.name();
        //#else
        //$$ return profile.getName();
        //#endif
    }

    /** A profile's UUID. */
    public static UUID id(GameProfile profile) {
        //#if MC >= 1.21.9
        return profile.id();
        //#else
        //$$ return profile.getId();
        //#endif
    }

    /** How many slots the main inventory has, hotbar included. */
    public static int mainSize(net.minecraft.entity.player.PlayerInventory inventory) {
        //#if MC >= 1.21.5
        return inventory.getMainStacks().size();
        //#else
        //$$ return inventory.main.size();
        //#endif
    }

    /** The selected hotbar slot. */
    public static int selectedSlot(net.minecraft.entity.player.PlayerInventory inventory) {
        //#if MC >= 1.21.5
        return inventory.getSelectedSlot();
        //#else
        //$$ return inventory.selectedSlot;
        //#endif
    }

    /** A string from a tag, or the fallback if it has none. */
    public static String nbtString(net.minecraft.nbt.NbtCompound tag, String key, String fallback) {
        //#if MC >= 1.21.5
        return tag.getString(key, fallback);
        //#else
        //$$ return tag.contains(key, net.minecraft.nbt.NbtElement.STRING_TYPE) ? tag.getString(key) : fallback;
        //#endif
    }

    /** A compound from a tag, or an empty one if it has none. */
    public static net.minecraft.nbt.NbtCompound nbtCompound(net.minecraft.nbt.NbtCompound tag, String key) {
        //#if MC >= 1.21.5
        return tag.getCompoundOrEmpty(key);
        //#else
        //$$ return tag.getCompound(key);
        //#endif
    }

    /** Where the mouse is, in GUI pixels. */
    public static double mouseX() {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        //#if MC >= 1.21.5
        return mc.mouse.getScaledX(mc.getWindow());
        //#else
        //$$ return mc.mouse.getX() * mc.getWindow().getScaledWidth() / mc.getWindow().getWidth();
        //#endif
    }

    /** Where the mouse is, in GUI pixels. */
    public static double mouseY() {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        //#if MC >= 1.21.5
        return mc.mouse.getScaledY(mc.getWindow());
        //#else
        //$$ return mc.mouse.getY() * mc.getWindow().getScaledHeight() / mc.getWindow().getHeight();
        //#endif
    }

    /** A texture made from an image, with a name for debugging where the version has one. */
    public static net.minecraft.client.texture.NativeImageBackedTexture texture(java.util.function.Supplier<String> name, net.minecraft.client.texture.NativeImage image) {
        //#if MC >= 1.21.5
        return new net.minecraft.client.texture.NativeImageBackedTexture(name, image);
        //#else
        //$$ return new net.minecraft.client.texture.NativeImageBackedTexture(image);
        //#endif
    }

    /** The sound of an item breaking. */
    public static net.minecraft.sound.SoundEvent itemBreakSound() {
        //#if MC >= 1.21.5
        return net.minecraft.sound.SoundEvents.ENTITY_ITEM_BREAK.value();
        //#else
        //$$ return net.minecraft.sound.SoundEvents.ENTITY_ITEM_BREAK;
        //#endif
    }
}
