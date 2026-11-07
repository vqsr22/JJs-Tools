package dev.jjstools.mixin;

import dev.jjstools.gui.jjtheme.ColumnStacks;
import dev.jjstools.gui.jjtheme.JJGuiTheme;
import meteordevelopment.meteorclient.gui.utils.Cell;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WWindow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.utils.Utils.getWindowHeight;
import static meteordevelopment.meteorclient.utils.Utils.getWindowWidth;

/**
 * Lays the module columns out in stacks under JJ's theme when any are stacked: Meteor's row from
 * the left, wrapping as Meteor's does, but each stack takes one place as wide as its widest
 * column. The columns lower in a stack then put themselves under the one above (WJJWindow), so
 * the windows are reordered to have every one laid out after the one above it.
 */
@Mixin(targets = "meteordevelopment.meteorclient.gui.screens.ModulesScreen$WCategoryController", remap = false)
public abstract class CategoryControllerMixin extends WContainer {
    @Inject(method = "onCalculateWidgetPositions", at = @At("HEAD"), cancellable = true, remap = false)
    private void jjsTools$stacks(CallbackInfo ci) {
        if (!(theme instanceof JJGuiTheme) || !ColumnStacks.any()) {
            ColumnStacks.clear();
            return;
        }
        ci.cancel();

        List<WWindow> windows = new ArrayList<>();
        List<Cell<?>> others = new ArrayList<>();
        java.util.Map<WWindow, Cell<?>> cellOf = new java.util.HashMap<>();
        for (Cell<?> cell : cells) {
            if (cell.widget() instanceof WWindow w && w.id != null) {
                windows.add(w);
                cellOf.put(w, cell);
            } else others.add(cell);
        }
        List<List<WWindow>> stacks = ColumnStacks.group(windows);

        // Every window after the one above it, as they are laid out in this order.
        List<Cell<?>> order = new ArrayList<>(others);
        for (List<WWindow> stack : stacks) for (WWindow w : stack) order.add(cellOf.get(w));
        cells.clear();
        cells.addAll(order);

        double pad = theme.scale(4);
        double h = theme.scale(40);
        ColumnStacks.gap = pad;
        double x = this.x + pad;
        double y = this.y;
        for (Cell<?> cell : others) place(cell, cell.widget().x, cell.widget().y);
        for (List<WWindow> stack : stacks) {
            double width = 0;
            for (WWindow w : stack) width = Math.max(width, w.width);
            double windowWidth = getWindowWidth(), windowHeight = getWindowHeight();
            if (x + width > windowWidth) {
                x = x + pad;
                y += h;
            }
            double cx = x, cy = y;
            if (cx > windowWidth) cx = Math.max(0, windowWidth / 2.0 - width / 2.0);
            if (cy > windowHeight) cy = Math.max(0, windowHeight / 2.0 - stack.getFirst().height / 2.0);
            // Each window starts where its stack does; the lower ones then move under the one above.
            for (WWindow w : stack) place(cellOf.get(w), cx, cy);
            x += width + pad;
        }
    }

    private static void place(Cell<?> cell, double x, double y) {
        cell.x = x;
        cell.y = y;
        cell.width = cell.widget().width;
        cell.height = cell.widget().height;
        cell.alignWidget();
    }
}
