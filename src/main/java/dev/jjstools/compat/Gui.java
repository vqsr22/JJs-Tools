package dev.jjstools.compat;

import net.minecraft.client.gui.DrawContext;

/**
 * GUI drawing calls that changed when Minecraft 1.21.6 rewrote how screens are drawn: the
 * matrices became a flat 2D stack, and textures are drawn with a render pipeline instead of a
 * render layer. Code that goes through here works on every version JJ's Tools builds for.
 */
public final class Gui {
    private Gui() {}

    /** What drawTexture and drawGuiTexture take for an ordinary textured quad. */
    //#if MC >= 1.21.6
    public static final com.mojang.blaze3d.pipeline.RenderPipeline TEXTURED = net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED;
    //#else
    //$$ public static final java.util.function.Function<net.minecraft.util.Identifier, net.minecraft.client.render.RenderLayer> TEXTURED = net.minecraft.client.render.RenderLayer::getGuiTextured;
    //#endif

    public static void push(DrawContext ctx) {
        //#if MC >= 1.21.6
        ctx.getMatrices().pushMatrix();
        //#else
        //$$ ctx.getMatrices().push();
        //#endif
    }

    public static void pop(DrawContext ctx) {
        //#if MC >= 1.21.6
        ctx.getMatrices().popMatrix();
        //#else
        //$$ ctx.getMatrices().pop();
        //#endif
    }

    public static void translate(DrawContext ctx, float x, float y) {
        //#if MC >= 1.21.6
        ctx.getMatrices().translate(x, y);
        //#else
        //$$ ctx.getMatrices().translate(x, y, 0.0F);
        //#endif
    }

    public static void scale(DrawContext ctx, float x, float y) {
        //#if MC >= 1.21.6
        ctx.getMatrices().scale(x, y);
        //#else
        //$$ ctx.getMatrices().scale(x, y, 1.0F);
        //#endif
    }

    /** A turn about the screen's depth axis, in radians. */
    public static void rotate(DrawContext ctx, float radians) {
        //#if MC >= 1.21.6
        ctx.getMatrices().rotate(radians);
        //#else
        //$$ ctx.getMatrices().multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Z.rotation(radians));
        //#endif
    }

    /** The current transform's x scale. */
    public static float scaleX(DrawContext ctx) {
        //#if MC >= 1.21.6
        return ctx.getMatrices().m00;
        //#else
        //$$ return ctx.getMatrices().peek().getPositionMatrix().m00();
        //#endif
    }

    /** The current transform's y scale. */
    public static float scaleY(DrawContext ctx) {
        //#if MC >= 1.21.6
        return ctx.getMatrices().m11;
        //#else
        //$$ return ctx.getMatrices().peek().getPositionMatrix().m11();
        //#endif
    }

    /** The current transform's x offset. */
    public static float offsetX(DrawContext ctx) {
        //#if MC >= 1.21.6
        return ctx.getMatrices().m20;
        //#else
        //$$ return ctx.getMatrices().peek().getPositionMatrix().m30();
        //#endif
    }

    /** The current transform's y offset. */
    public static float offsetY(DrawContext ctx) {
        //#if MC >= 1.21.6
        return ctx.getMatrices().m21;
        //#else
        //$$ return ctx.getMatrices().peek().getPositionMatrix().m31();
        //#endif
    }

    /** Resets the current transform to none. */
    public static void identity(DrawContext ctx) {
        //#if MC >= 1.21.6
        ctx.getMatrices().identity();
        //#else
        //$$ ctx.getMatrices().peek().getPositionMatrix().identity();
        //#endif
    }

    /** The GUI scale, in screen pixels per GUI pixel. A whole number on every version. */
    public static int scaleFactor() {
        //#if MC >= 1.21.6
        return net.minecraft.client.MinecraftClient.getInstance().getWindow().getScaleFactor();
        //#else
        //$$ return (int) Math.round(net.minecraft.client.MinecraftClient.getInstance().getWindow().getScaleFactor());
        //#endif
    }

    /** Starts a new layer, so what is drawn next goes over everything drawn so far. */
    public static void newLayer(DrawContext ctx) {
        //#if MC >= 1.21.6
        ctx.createNewRootLayer();
        //#else
        //$$ // Drawn straight away before 1.21.6, so what is queued is flushed and later draws go on top.
        //$$ ctx.draw();
        //#endif
    }
}
