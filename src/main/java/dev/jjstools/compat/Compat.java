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
}
