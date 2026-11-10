package dev.jjstools.mixin;

import dev.jjstools.mixin.accessor.ScreenTitleAccessor;
import dev.jjstools.modules.StorageGuiTweaks;
import dev.jjstools.tint.ShulkerScreenTint;
import meteordevelopment.meteorclient.utils.render.PeekScreen;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Colours Meteor's shulker peek screen the same way as a box opened in the world.
 *
 * Middle-clicking a shulker box with Better Tooltips opens Meteor's own copy of the shulker
 * screen. It draws its background itself, the whole texture multiplied by the box's colour, so
 * the inventory half came out tinted and dark too and none of Storage GUI Tweaks' settings
 * applied. That screen knows which item it is showing, so the colour is read straight from that
 * and the panel and title are drawn exactly as the real screen draws them.
 *
 * The target is Meteor's class rather than the game's, so the names here are not remapped:
 * method_2389 is the game's drawBackground, which Meteor overrides, under the name it has in a
 * built game. An ender chest peek is drawn like the real ender chest screen; anything else is left
 * to Meteor.
 */
@Mixin(value = PeekScreen.class, remap = false)
public abstract class PeekScreenMixin extends ShulkerBoxScreen {
    @Shadow @Final private ItemStack storageBlock;

    @Unique private Integer jjsTools$boxRgb;
    @Unique private boolean jjsTools$colorized;

    protected PeekScreenMixin(ShulkerBoxScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
    }

    /** After Meteor's constructor, once it has set which item it is showing. */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void jjsTools$colour(CallbackInfo ci) {
        if (!StorageGuiTweaks.on()) return;

        jjsTools$boxRgb = ShulkerScreenTint.colourOf(storageBlock);
        if (jjsTools$boxRgb == null) return;

        jjsTools$colorized = ShulkerScreenTint.prepare(jjsTools$boxRgb);

        Text recoloured = ShulkerScreenTint.title(storageBlock.getName(), jjsTools$boxRgb);
        if (recoloured != null) ((ScreenTitleAccessor) (Object) this).jjsTools$setTitle(recoloured);
    }

    @Inject(method = "method_2389", at = @At("HEAD"), cancellable = true)
    private void jjsTools$drawTinted(DrawContext context, float deltaTicks, int mouseX, int mouseY, CallbackInfo ci) {
        if (ShulkerScreenTint.draw(context, x, y, backgroundWidth, backgroundHeight, jjsTools$boxRgb, jjsTools$colorized)) {
            ci.cancel();
            return;
        }

        if (StorageGuiTweaks.on() && jjsTools$isEnderChest()) {
            jjsTools$drawEnderChest(context);
            ci.cancel();
        }
    }

    @Unique
    private boolean jjsTools$isEnderChest() {
        return storageBlock != null && storageBlock.getItem() instanceof BlockItem item && item.getBlock() instanceof EnderChestBlock;
    }

    /**
     * An ender chest peek drawn the way the real ender chest screen is, from your pack's chest
     * texture and in its own colours.
     *
     * Meteor shows ender chest contents in the same shulker-shaped screen it uses for boxes, and
     * multiplies the whole texture by a colour it picks for the item, which for an ender chest is
     * not a colour that belongs on it. The real ender chest screen is the chest one: the top of
     * the chest texture for three rows of slots, then its inventory half. The peek screen is two
     * pixels shorter than that and its inventory slots sit one pixel higher, so the inventory half
     * is drawn from one row further down to put its slots under the items.
     */
    @Unique
    private void jjsTools$drawEnderChest(DrawContext context) {
        int top = 17 + 3 * 18;
        context.drawTexture(dev.jjstools.compat.Gui.TEXTURED, CHEST_TEXTURE, x, y, 0.0F, 0.0F, backgroundWidth, top, 256, 256);
        context.drawTexture(dev.jjstools.compat.Gui.TEXTURED, CHEST_TEXTURE, x, y + top, 0.0F, 127.0F,
            backgroundWidth, backgroundHeight - top, 256, 256);
    }

    @Unique
    private static final Identifier CHEST_TEXTURE = Identifier.ofVanilla("textures/gui/container/generic_54.png");
}
