package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import dev.jjstools.tint.Colors;
import dev.jjstools.tint.StorageMemory;
import dev.jjstools.tint.StorageSummary;
import dev.jjstools.tint.TintedTexture;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tweaks to the GUIs of storage: shulker boxes, chests, barrels and the ender chest.
 *
 * Shulker boxes get their GUI and title coloured to match the box. That does not replace your
 * pack's texture: it reads whatever shulker_box.png the pack provides and recolours it pixel by
 * pixel, using the same maths ShulkerBoxTooltip uses, so the two agree on what colour a box is.
 *
 * All of them get the crosshair preview, drawn with your pack's own GUI for that container, and a
 * long name fitted to the panel. Chests, barrels and the ender chest are left in your pack's own
 * colours: they have no dye to match.
 *
 * Was Shulker Tweaks, and before that a separate mod.
 */
public class StorageGuiTweaks extends Module {
    public enum TitleMode { Vanilla, MatchBox, InverseBrightness, Custom }

    /** How a name too long for the panel is made to fit. */
    public enum TitleFit { Spaces, Aliasing, Scale }
    public enum BlendMode { Colourise, Multiply }

    /** How the crosshair preview comes and goes. */
    public enum Animation { None, Fade, Slide }

    /** The shape of the motion. Ease Out starts quick and settles, which suits something appearing. */
    public enum Easing { Linear, EaseIn, EaseOut, EaseInOut }

    /** The way the preview travels as it appears. */
    public enum SlideDirection { Up, Down, Left, Right }

    /** Which item in a container its icon shows. */
    public enum IconChoice { Most, Least, First, Last, OnlyIfOneKind }

    /** How a container's custom name picks its icon. */
    public enum NameUse { Ignore, Prefer, Only }

    /** Which way the fill bar fills. */
    public enum BarDirection { LeftToRight, RightToLeft, BottomToTop, TopToBottom }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPreview = settings.createGroup("Crosshair Preview");
    private final SettingGroup sgChests = settings.createGroup("Storage Previews");
    private final SettingGroup sgTooltips = settings.createGroup("Storage Tooltips");
    private final SettingGroup sgIcons = settings.createGroup("Item Icons");
    private final SettingGroup sgBar = settings.createGroup("Fill Bar");
    private final SettingGroup sgTitle = settings.createGroup("Title");
    private final SettingGroup sgColours = settings.createGroup("Colours");

    // General

    private final Setting<Boolean> tintBackground = sgGeneral.add(new BoolSetting.Builder()
        .name("tint-background")
        .description("Recolour a shulker box's GUI panel itself, not just the title.")
        .defaultValue(true)
        .build()
    );

    private final Setting<BlendMode> blendMode = sgGeneral.add(new EnumSetting.Builder<BlendMode>()
        .name("blend-mode")
        .description("Colourise rebuilds the texture and keeps its shading. Multiply is cheaper but goes muddy on dark packs.")
        .defaultValue(BlendMode.Colourise)
        .visible(tintBackground::get)
        .build()
    );

    private final Setting<Integer> tintStrength = sgGeneral.add(new IntSetting.Builder()
        .name("tint-strength")
        .description("How far towards the box colour the panel goes.")
        .defaultValue(100).min(0).max(100).sliderRange(0, 100)
        .visible(tintBackground::get)
        .build()
    );

    private final Setting<Integer> tintHeight = sgGeneral.add(new IntSetting.Builder()
        .name("tint-height")
        .description("How far down the opened shulker GUI is tinted, in pixels. 78 is the box's half including the divider; raise it towards 166 to tint your inventory too. The crosshair preview has its own.")
        .defaultValue(78).min(0).max(166).sliderRange(0, 166)
        .visible(tintBackground::get)
        .build()
    );

    // Title

    private final Setting<TitleMode> titleMode = sgTitle.add(new EnumSetting.Builder<TitleMode>()
        .name("title-mode")
        .description("Inverse Brightness keeps the box's hue but flips its lightness, so a dark box gets a light title.")
        .defaultValue(TitleMode.InverseBrightness)
        .build()
    );

    private final Setting<Integer> titleBrightness = sgTitle.add(new IntSetting.Builder()
        .name("title-brightness")
        .description("Lighter or darker than the box colour.")
        .defaultValue(60).min(-100).max(100).sliderRange(-100, 100)
        .visible(() -> titleMode.get() == TitleMode.MatchBox)
        .build()
    );

    private final Setting<Integer> inverseStrength = sgTitle.add(new IntSetting.Builder()
        .name("inverse-strength")
        .description("How far the title's lightness is flipped away from the box's.")
        .defaultValue(80).min(0).max(100).sliderRange(0, 100)
        .visible(() -> titleMode.get() == TitleMode.InverseBrightness)
        .build()
    );

    private final Setting<SettingColor> customTitleColour = sgTitle.add(new ColorSetting.Builder()
        .name("custom-title-colour")
        .defaultValue(new SettingColor(255, 255, 255))
        .visible(() -> titleMode.get() == TitleMode.Custom)
        .build()
    );

    private final Setting<Boolean> shrinkTitle = sgTitle.add(new BoolSetting.Builder()
        .name("shrink-long-titles")
        .description("Shrink a container's name when it is too long to fit, instead of letting it run off the side of the GUI. Applies to shulker boxes, and to chests, barrels and ender chests that are turned on below.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> fitToSlots = sgTitle.add(new BoolSetting.Builder()
        .name("fit-to-slots")
        .description("Fit the name to the slots themselves, from the left edge of the first to the right edge of the last, instead of to a width you set.")
        .defaultValue(true)
        .visible(shrinkTitle::get)
        .build()
    );

    private final Setting<Integer> titleWidth = sgTitle.add(new IntSetting.Builder()
        .name("title-width")
        .description("How wide the name may be before it is shrunk, in GUI pixels. A shulker's row of slots is 160 wide.")
        .defaultValue(160).min(20).max(250).sliderRange(40, 200)
        .visible(() -> shrinkTitle.get() && !fitToSlots.get())
        .build()
    );

    private final Setting<TitleFit> titleFit = sgTitle.add(new EnumSetting.Builder<TitleFit>()
        .name("fit-mode")
        .description("How a name too long for the panel is made to fit. Spaces keeps the letters at a size where every font pixel is a whole screen pixel square and takes the difference out of the gaps between words, so a name only a little too long is not shrunk at all. Aliasing holds to those same sizes but changes nothing else, so the name can stop short of the end of the row. Scale takes exactly the width available, which fills the row but leaves some pixels longer than they are wide.")
        .defaultValue(TitleFit.Scale)
        .visible(shrinkTitle::get)
        .build()
    );

    private final Setting<Boolean> overrideNameColours = sgTitle.add(new BoolSetting.Builder()
        .name("override-name-colours")
        .description("Ignore colours baked into a box's custom name, so the chosen title colour always shows.")
        .defaultValue(true)
        .build()
    );

    // Crosshair preview

    private final Setting<Boolean> preview = sgPreview.add(new BoolSetting.Builder()
        .name("crosshair-preview")
        .description("Show what is inside a placed container while your crosshair is on it, drawn with your resource pack's GUI for it, a shulker coloured to match the box. The server never sends a placed container's contents, so this works from what you have seen: containers you have opened, and shulker boxes you placed from your hand.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> previewX = sgPreview.add(new IntSetting.Builder()
        .name("preview-x")
        .description("Move the preview left or right from the middle of the screen, in GUI pixels.")
        .defaultValue(0)
        .min(-2000).max(2000)
        .sliderRange(-400, 400)
        .visible(preview::get)
        .build()
    );

    private final Setting<Integer> previewY = sgPreview.add(new IntSetting.Builder()
        .name("preview-y")
        .description("How far down from the top of the screen the preview sits, in GUI pixels.")
        .defaultValue(10)
        .min(-2000).max(2000)
        .sliderRange(0, 400)
        .visible(preview::get)
        .build()
    );

    private final Setting<Double> previewSize = sgPreview.add(new DoubleSetting.Builder()
        .name("preview-size")
        .description("How big the preview is drawn. One is the size of the real GUI.")
        .defaultValue(1.0)
        .min(0.25).max(3.0)
        .sliderRange(0.5, 2.0)
        .visible(preview::get)
        .build()
    );

    private final Setting<Integer> previewTintHeight = sgPreview.add(new IntSetting.Builder()
        .name("preview-tint-height")
        .description("How far down the preview is tinted, measured on the same 0 to 166 scale as tint-height so the same number means the same row of your pack's texture. The preview shows rows 0 to 74 of the box, then the 3 rows of bottom edge from 163 to 165, so 75 tints the box and leaves the edge as your pack draws it and 166 tints the edge too.")
        .defaultValue(78)
        .min(0).max(166)
        .sliderRange(0, 166)
        .visible(() -> preview.get() && tintBackground.get())
        .build()
    );

    private final Setting<Integer> bottomShade = sgPreview.add(new IntSetting.Builder()
        .name("bottom-edge-shade")
        .description("Draws the preview's bottom edge as a darker shade of the panel's own colour, so it reads as a raised 3D panel. Higher is darker. At 0 the edge is drawn as your pack has it, which in a pack with a dark inventory half is near black.")
        .defaultValue(55)
        .min(0).max(100)
        .sliderRange(0, 100)
        .visible(preview::get)
        .build()
    );

    private final Setting<Animation> animation = sgPreview.add(new EnumSetting.Builder<Animation>()
        .name("animation")
        .description("How the preview comes and goes. Fade fades it in and out. Slide moves it in from one side and fades it as it goes.")
        .defaultValue(Animation.Fade)
        .visible(preview::get)
        .build()
    );

    private final Setting<Integer> animationTime = sgPreview.add(new IntSetting.Builder()
        .name("animation-time")
        .description("How long the preview takes to appear or disappear, in milliseconds.")
        .defaultValue(200)
        .min(0).max(2000)
        .sliderRange(0, 600)
        .visible(() -> preview.get() && animation.get() != Animation.None)
        .build()
    );

    private final Setting<Easing> easing = sgPreview.add(new EnumSetting.Builder<Easing>()
        .name("easing")
        .description("The shape of the motion. Ease In starts slow, Ease Out finishes slow, Ease In Out does both, Linear does neither.")
        .defaultValue(Easing.EaseInOut)
        .visible(() -> preview.get() && animation.get() != Animation.None)
        .build()
    );

    private final Setting<Boolean> fadeItems = sgPreview.add(new BoolSetting.Builder()
        .name("fade-items")
        .description("Fades the items in and out instead of popping them in halfway. The panel fades first, then the items fade in on top of it, and the other way round on the way out.")
        .defaultValue(true)
        .visible(() -> preview.get() && animation.get() != Animation.None)
        .build()
    );

    private final Setting<SlideDirection> slideDirection = sgPreview.add(new EnumSetting.Builder<SlideDirection>()
        .name("slide-direction")
        .description("Which way the preview travels as it appears. Down comes in from above, Up from below, Left from the right and Right from the left.")
        .defaultValue(SlideDirection.Down)
        .visible(() -> preview.get() && animation.get() == Animation.Slide)
        .build()
    );

    private final Setting<Integer> slideDistance = sgPreview.add(new IntSetting.Builder()
        .name("slide-distance")
        .description("How far the preview travels, in GUI pixels.")
        .defaultValue(30)
        .min(0).max(1000)
        .sliderRange(0, 200)
        .visible(() -> preview.get() && animation.get() == Animation.Slide)
        .build()
    );

    // Storage previews

    private final Setting<Boolean> chests = sgChests.add(new BoolSetting.Builder()
        .name("chest-preview")
        .description("Preview and fit the names of chests and trapped chests, single and double. Copper chests are left alone.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> enderChests = sgChests.add(new BoolSetting.Builder()
        .name("ender-chest-preview")
        .description("Preview your ender chest. It holds the same things wherever you open it, so after opening any one, every ender chest you look at shows it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> barrels = sgChests.add(new BoolSetting.Builder()
        .name("barrel-preview")
        .description("Preview and fit the names of barrels.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> shulkerInventory = sgTooltips.add(new BoolSetting.Builder()
        .name("shulker-storage-tooltip")
        .description("Hovering a shulker box in an inventory shows the box itself, drawn like the open box with its name at the top, instead of the item's tooltip.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> enderInventory = sgTooltips.add(new BoolSetting.Builder()
        .name("ender-chest-storage-tooltip")
        .description("Hovering an ender chest in an inventory shows what is in your ender chest instead of the item's tooltip. Needs an ender chest to have been opened on this server once, so there is something to show.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> enderTooltipName = sgTooltips.add(new BoolSetting.Builder()
        .name("ender-chest-tooltip-name")
        .description("Show the ender chest's name across the top of its storage tooltip, above the items.")
        .defaultValue(true)
        .visible(enderInventory::get)
        .build()
    );

    private final Setting<SettingColor> enderTooltipNameColour = sgTooltips.add(new ColorSetting.Builder()
        .name("ender-chest-name-colour")
        .description("Colour of that name. Light by default so it reads on darker chest textures; vanilla's own is a dark grey, 64 64 64.")
        .defaultValue(new SettingColor(230, 230, 230))
        .visible(() -> enderInventory.get() && enderTooltipName.get())
        .build()
    );

    private final Setting<Integer> chestLidRows = sgChests.add(new IntSetting.Builder()
        .name("lid-rows")
        .description("How many rows of your pack's chest texture to show below the slots before the bottom edge, in pixels. Your pack draws the bottom of the chest's panel at the top of the inventory half, so this is how much of it to bring in. Raise it until the preview ends where your pack's chest panel does.")
        .defaultValue(4)
        .min(0).max(40)
        .sliderRange(0, 20)
        .build()
    );

    // Item icons

    private final Setting<Boolean> shulkerIcons = sgIcons.add(new BoolSetting.Builder()
        .name("shulker-icons")
        .description("Draws a small icon of what is inside on every shulker box item, in inventories and on the hotbar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> enderIcons = sgIcons.add(new BoolSetting.Builder()
        .name("ender-chest-icons")
        .description("The same icon on ender chest items, from what was in your ender chest the last time you opened one on this server.")
        .defaultValue(false)
        .build()
    );

    private final Setting<IconChoice> iconChoice = sgIcons.add(new EnumSetting.Builder<IconChoice>()
        .name("icon-item")
        .description("Which item the icon shows. Most and Least go by how many of it there are in all, First and Last by where it sits in the box, and Only If One Kind shows an icon only when everything in the box is the same thing.")
        .defaultValue(IconChoice.First)
        .build()
    );

    private final Setting<NameUse> nameUse = sgIcons.add(new EnumSetting.Builder<NameUse>()
        .name("use-custom-name")
        .description("Let a box's custom name pick the icon. Prefer shows the item the name points at if the box has any, and falls back to icon-item. Only shows an icon only when the name points at something in the box. Words in the name are matched against item names, so a box called Chorus picks chorus fruit.")
        .defaultValue(NameUse.Prefer)
        .build()
    );

    private final Setting<StorageSummary.Grouping> grouping = sgIcons.add(new EnumSetting.Builder<StorageSummary.Grouping>()
        .name("grouping")
        .description("What counts as the same item when adding them up. Item counts any of one item together. Enchantments keeps enchanted and plain apart. Exact keeps anything with different data apart, such as potions with different effects.")
        .defaultValue(StorageSummary.Grouping.Enchantments)
        .build()
    );

    private final Setting<Integer> minCount = sgIcons.add(new IntSetting.Builder()
        .name("min-count")
        .description("How many of the chosen item there must be in all before its icon is drawn.")
        .defaultValue(1)
        .min(1).max(1728)
        .sliderRange(1, 128)
        .build()
    );

    private final Setting<Integer> iconSize = sgIcons.add(new IntSetting.Builder()
        .name("icon-size")
        .description("How big the icon is, in pixels. A full item is 16. It grows and shrinks about its own centre, so changing this leaves it where it is.")
        .defaultValue(13)
        .min(1).max(16)
        .sliderRange(1, 16)
        .build()
    );

    private final Setting<Boolean> pixelPerfect = sgIcons.add(new BoolSetting.Builder()
        .name("aliasing")
        .description("Keep the icon's pixels square and even. An item is 16 pixels of art, and at most sizes those cannot be shared out evenly over the screen pixels, so some come out doubled and some not, which looks wonky. This rounds the size to the nearest one where every pixel of art is the same whole number of screen pixels, and puts it on a whole screen pixel. Which sizes those are depends on your GUI scale: at scale 2 only 8 and 16, at scale 4 also 4 and 12.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> iconX = sgIcons.add(new IntSetting.Builder()
        .name("icon-x")
        .description("How far the icon's centre sits right of the slot's centre, in pixels. Negative is left.")
        .defaultValue(0)
        .min(-4).max(4)
        .sliderRange(-4, 4)
        .build()
    );

    private final Setting<Integer> iconY = sgIcons.add(new IntSetting.Builder()
        .name("icon-y")
        .description("How far the icon's centre sits above the slot's centre, in pixels. Negative is down.")
        .defaultValue(0)
        .min(-4).max(4)
        .sliderRange(-4, 4)
        .build()
    );

    private final Setting<Boolean> iconBehindBar = sgIcons.add(new BoolSetting.Builder()
        .name("icon-behind-fill-bar")
        .description("Draw the icon underneath the fill bar, so the bar stays readable where the two overlap. Off puts the icon on top.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> moveForCount = sgIcons.add(new BoolSetting.Builder()
        .name("move-for-stack-count")
        .description("On a stack of more than one box, put the icon somewhere else so the stack's count stays readable.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> stackedIconX = sgIcons.add(new IntSetting.Builder()
        .name("stacked-icon-x")
        .description("Icon centre's distance right of the slot's centre on a stack of boxes, in pixels.")
        .defaultValue(0)
        .min(-4).max(4)
        .sliderRange(-4, 4)
        .visible(moveForCount::get)
        .build()
    );

    private final Setting<Integer> stackedIconY = sgIcons.add(new IntSetting.Builder()
        .name("stacked-icon-y")
        .description("Icon centre's distance above the slot's centre on a stack of boxes, in pixels.")
        .defaultValue(0)
        .min(-4).max(4)
        .sliderRange(-4, 4)
        .visible(moveForCount::get)
        .build()
    );

    // Fill bar

    private final Setting<Boolean> shulkerFillBar = sgBar.add(new BoolSetting.Builder()
        .name("shulker-fill-bar")
        .description("Draws a bar on shulker box items showing how full they are, like a durability bar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> enderFillBar = sgBar.add(new BoolSetting.Builder()
        .name("ender-chest-fill-bar")
        .description("The same bar on ender chest items, for how full your ender chest was the last time you opened one on this server.")
        .defaultValue(false)
        .build()
    );

    private final Setting<StorageSummary.Fill> fillMeasure = sgBar.add(new EnumSetting.Builder<StorageSummary.Fill>()
        .name("measure")
        .description("Slots counts how many slots have anything in them. Items counts how much room is used, so a slot of 32 out of 64 is half a slot.")
        .defaultValue(StorageSummary.Fill.Items)
        .visible(this::anyFillBar)
        .build()
    );

    private final Setting<BarDirection> barDirection = sgBar.add(new EnumSetting.Builder<BarDirection>()
        .name("direction")
        .description("Which way the bar fills.")
        .defaultValue(BarDirection.LeftToRight)
        .visible(this::anyFillBar)
        .build()
    );

    private final Setting<Integer> barX = sgBar.add(new IntSetting.Builder()
        .name("bar-x")
        .description("Left edge of the bar, in pixels from the slot's left.")
        .defaultValue(2)
        .min(-4).max(16)
        .sliderRange(0, 16)
        .visible(this::anyFillBar)
        .build()
    );

    private final Setting<Integer> barY = sgBar.add(new IntSetting.Builder()
        .name("bar-y")
        .description("Top edge of the bar, in pixels from the slot's top. 13 is where a durability bar goes.")
        .defaultValue(13)
        .min(-4).max(16)
        .sliderRange(0, 16)
        .visible(this::anyFillBar)
        .build()
    );

    private final Setting<Integer> barLength = sgBar.add(new IntSetting.Builder()
        .name("bar-length")
        .description("How long the bar is, in pixels, along the way it fills.")
        .defaultValue(13)
        .min(1).max(20)
        .sliderRange(1, 16)
        .visible(this::anyFillBar)
        .build()
    );

    private final Setting<Integer> barThickness = sgBar.add(new IntSetting.Builder()
        .name("bar-thickness")
        .description("How thick the bar is, in pixels.")
        .defaultValue(1)
        .min(1).max(8)
        .sliderRange(1, 4)
        .visible(this::anyFillBar)
        .build()
    );

    private final Setting<Boolean> barBackground = sgBar.add(new BoolSetting.Builder()
        .name("background")
        .description("Draw the empty part of the bar and a border under it, like a durability bar.")
        .defaultValue(true)
        .visible(this::anyFillBar)
        .build()
    );

    private final Setting<SettingColor> barColour = sgBar.add(new ColorSetting.Builder()
        .name("bar-colour")
        .defaultValue(new SettingColor(112, 134, 255))
        .visible(this::anyFillBar)
        .build()
    );

    private final Setting<SettingColor> fullColour = sgBar.add(new ColorSetting.Builder()
        .name("full-colour")
        .description("The bar's colour once the box is completely full.")
        .defaultValue(new SettingColor(255, 85, 85))
        .visible(this::anyFillBar)
        .build()
    );

    private final Setting<SettingColor> backgroundColour = sgBar.add(new ColorSetting.Builder()
        .name("background-colour")
        .defaultValue(new SettingColor(0, 0, 0))
        .visible(() -> anyFillBar() && barBackground.get())
        .build()
    );

    private final Setting<Boolean> hideEmpty = sgBar.add(new BoolSetting.Builder()
        .name("hide-when-empty")
        .defaultValue(true)
        .visible(this::anyFillBar)
        .build()
    );

    private final Setting<Boolean> hideFull = sgBar.add(new BoolSetting.Builder()
        .name("hide-when-full")
        .defaultValue(false)
        .visible(this::anyFillBar)
        .build()
    );

    // Colours

    private final Setting<SettingColor> undyedColour = sgColours.add(new ColorSetting.Builder()
        .name("undyed")
        .description("An undyed box has no dye colour to read, so this is used instead.")
        .defaultValue(rgb(Colors.DEFAULT_UNDYED))
        .build()
    );

    /**
     * One colour setting per dye.
     *
     * Built from DyeColor rather than written out sixteen times, so a new dye colour would appear
     * on its own and the defaults stay tied to the shared colour table.
     */
    private final Map<DyeColor, Setting<SettingColor>> dyeColours = new EnumMap<>(DyeColor.class);

    public StorageGuiTweaks() {
        super(JJsTools.CATEGORY, "storage-GUI-tweaks", "All your storage customisation needs! Shows what is inside placed shulker boxes, chests, barrels and ender chests when you look at them, colours the shulker GUI to match the box, and fits long names.");

        for (DyeColor dye : DyeColor.values()) {
            dyeColours.put(dye, sgColours.add(new ColorSetting.Builder()
                // DyeColor has no getName() here; the mod's own helper is what the old settings page used.
                .name(Colors.prettyName(dye).toLowerCase(java.util.Locale.ROOT).replace(' ', '-').replace("gray", "grey"))
                .defaultValue(rgb(Colors.defaultDyeRgb(dye)))
                .build()
            ));
        }
    }

    // Crosshair preview

    /** Your resource pack's shulker GUI, the same texture the real screen draws. */
    private static final Identifier TEXTURE = Identifier.ofVanilla("textures/gui/container/shulker_box.png");

    /**
     * Your resource pack's chest GUI. Every chest-style screen draws from this one texture, single
     * chests, double chests, barrels and the ender chest alike: the top of it for the rows of
     * slots, cut off after the last one, and the inventory half from row 126 down.
     */
    private static final Identifier CHEST_TEXTURE = Identifier.ofVanilla("textures/gui/container/generic_54.png");

    /** Where the chest texture's inventory half starts, and where its bottom edge sits. */
    private static final int CHEST_INVENTORY_V = 126;
    private static final int CHEST_BOTTOM_V = 219;

    /*
     * The preview is the real GUI's top, rows 0 to 74, closed off with the real GUI's own bottom
     * edge, rows 163 to 165. Read off the vanilla texture: the slots end on row 70 and the box's
     * own panel carries on below them, while 163 and 164 are the bevel and 165 the outline.
     *
     * The edge used to start at 159, which took four rows of panel from below the inventory as
     * well. Packs that draw the inventory half darker than the box showed those as a dark band
     * along the bottom that no tint height could fix, and tinting it only made a darker tint.
     * The panel now comes from the box itself, so the only rows borrowed from the inventory half
     * are the edge, which a pack draws the same all the way round.
     */
    private static final int WIDTH = 176;
    private static final int TOP_HEIGHT = 75;
    private static final int BOTTOM_V = 163;
    private static final int BOTTOM_HEIGHT = 3;

    /** The two bevel rows of the bottom edge, and the box panel row they are shaded from. */
    private static final int BEVEL_HEIGHT = 2;
    private static final int SHADE_SOURCE_V = 74;

    /**
     * Items cannot be drawn partly transparent: nothing in the game's GUI pipeline takes an alpha
     * for an item. So while the panel fades, the items come in once it is past this point and
     * go once it drops below it.
     */
    private static final float ITEM_ALPHA = 0.5F;

    /**
     * With fade-items on, how much of the animation the panel's fade takes. The items fade
     * through the rest, over a panel that is by then fully solid.
     */
    private static final float PANEL_SHARE = 0.6F;

    /** Vanilla's container title colour, used when the title mode leaves it alone. */
    private static final int VANILLA_TITLE = 0x404040;

    private float progress;
    private long lastFrame;
    private StorageMemory.Box shownBox;
    private DyeColor shownColour;
    private String shownTitle;

    /** One recoloured copy of the pack texture per box colour, built the first time it is needed. */
    private final Map<Long, Identifier> tinted = new HashMap<>();

    @Override
    public void onActivate() {
        progress = 0.0F;
        lastFrame = 0L;
        shownBox = null;
    }

    @Override
    public void onDeactivate() {
        StorageMemory.onLeave();
        forgetTextures();
        shownBox = null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        checkSummaries();
        StorageMemory.tick();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        StorageMemory.onLeave();
        shownBox = null;
        progress = 0.0F;
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!preview.get() || mc.world == null) {
            progress = 0.0F;
            return;
        }

        BlockPos target = lookedAt();
        StorageMemory.Box box = target == null ? null : StorageMemory.get(target);
        boolean showing = box != null;

        // Kept after you look away, so the box fading out is the box you were looking at.
        if (showing) {
            BlockState state = mc.world.getBlockState(target);
            shownBox = box;
            shownColour = state.getBlock() instanceof ShulkerBoxBlock shulker ? shulker.getColor() : null;
            shownTitle = box.name() != null ? box.name() : defaultTitle(box, state);
        }

        long now = System.nanoTime();
        float seconds = lastFrame == 0L ? 0.0F : Math.min(0.1F, (now - lastFrame) / 1_000_000_000.0F);
        lastFrame = now;

        int time = animationTime.get();
        if (animation.get() == Animation.None || time <= 0) {
            progress = showing ? 1.0F : 0.0F;
        }
        else {
            float step = seconds * 1000.0F / time;
            progress = Math.max(0.0F, Math.min(1.0F, progress + (showing ? step : -step)));
        }

        if (progress <= 0.0F || shownBox == null) return;
        draw(event.drawContext, event.screenWidth, eased(progress));
    }

    /**
     * The placed container under your crosshair, within reach, if it is a kind that is turned on.
     *
     * Not while a screen is up or the HUD is hidden, where it would only be in the way.
     */
    private BlockPos lookedAt() {
        if (mc.player == null || mc.currentScreen != null || mc.options.hudHidden) return null;
        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return null;

        StorageMemory.Kind kind = StorageMemory.kindOf(mc.world.getBlockState(hit.getBlockPos()).getBlock());
        if (kind == null || !enabled(kind)) return null;
        return hit.getBlockPos();
    }

    private boolean enabled(StorageMemory.Kind kind) {
        return switch (kind) {
            case Shulker -> true;
            case Chest -> chests.get();
            case Barrel -> barrels.get();
            case Ender -> enderChests.get();
        };
    }

    /** What the real screen would be titled, for a container with no name of its own. */
    private static String defaultTitle(StorageMemory.Box box, BlockState state) {
        if (box.kind() == StorageMemory.Kind.Chest && box.rows() > 3) return Text.translatable("container.chestDouble").getString();
        return state.getBlock().getName().getString();
    }

    /** Whether the shown container is a shulker box, which is the only kind that is drawn tinted. */
    private boolean shulker() {
        return shownBox != null && shownBox.kind() == StorageMemory.Kind.Shulker;
    }

    /**
     * Where the preview's pieces come from in its texture.
     *
     * A shulker is the box's top, rows 0 to 74, then the bottom edge from 163. A chest is its top
     * cut off after the last row of slots, as the real screen cuts it, then the pack's panel
     * from the top of the inventory half for as many rows as lid-rows asks, then the bottom edge
     * from 219. The shade for the bevel is taken from the last row of panel above it.
     */
    private record Layout(Identifier texture, int topHeight, int lidV, int lidHeight, int bottomV, int shadeV) {
        int height() {
            return topHeight + lidHeight + BOTTOM_HEIGHT;
        }
    }

    private Layout layout() {
        if (shulker()) return new Layout(TEXTURE, TOP_HEIGHT, 0, 0, BOTTOM_V, SHADE_SOURCE_V);

        int top = 17 + shownBox.rows() * 18;
        int lid = chestLidRows.get();
        int shadeV = lid > 0 ? CHEST_INVENTORY_V + lid - 1 : top - 1;
        return new Layout(CHEST_TEXTURE, top, CHEST_INVENTORY_V, lid, CHEST_BOTTOM_V, shadeV);
    }

    private float eased(float t) {
        return switch (easing.get()) {
            case Linear -> t;
            case EaseIn -> t * t * t;
            case EaseOut -> 1.0F - (float) Math.pow(1.0F - t, 3.0);
            case EaseInOut -> t < 0.5F ? 4.0F * t * t * t : 1.0F - (float) Math.pow(-2.0F * t + 2.0F, 3.0) / 2.0F;
        };
    }

    /**
     * Lays the preview out and draws it.
     *
     * Position and size are applied once as a transform around everything, so the panel, the
     * items and the title move and scale together and none of them has to know about it.
     */
    private void draw(DrawContext context, int screenWidth, float shown) {
        Animation mode = animation.get();
        float alpha = mode == Animation.None ? 1.0F : shown;

        float dx = 0.0F;
        float dy = 0.0F;
        if (mode == Animation.Slide) {
            float distance = (1.0F - shown) * slideDistance.get();
            switch (slideDirection.get()) {
                case Up -> dy = distance;
                case Down -> dy = -distance;
                case Left -> dx = distance;
                case Right -> dx = -distance;
            }
        }

        float scale = previewSize.get().floatValue();
        float x = screenWidth / 2.0F - WIDTH * scale / 2.0F + previewX.get() + dx;
        float y = previewY.get() + dy;

        context.getMatrices().pushMatrix();
        context.getMatrices().translate(x, y);
        context.getMatrices().scale(scale, scale);

        Look look = look();

        if (mode != Animation.None && fadeItems.get()) {
            /*
             * Items cannot be drawn see-through, so they are faded by covering them instead: each
             * filled slot is drawn again on top of its item, from the same texture, and that cover
             * fades out. Doing it over a part-transparent panel would leave the covered slots
             * looking more solid than the rest, so the panel finishes its own fade first.
             */
            float panelAlpha = Math.min(1.0F, shown / PANEL_SHARE);
            float itemsShown = Math.max(0.0F, (shown - PANEL_SHARE) / (1.0F - PANEL_SHARE));

            drawPanel(context, look, panelAlpha);
            if (itemsShown > 0.0F) {
                drawItems(context);
                if (itemsShown < 1.0F) coverItems(context, look, 1.0F - itemsShown);
            }
            drawTitle(context, panelAlpha);
        }
        else {
            drawPanel(context, look, alpha);
            if (alpha >= ITEM_ALPHA) drawItems(context);
            drawTitle(context, alpha);
        }

        context.getMatrices().popMatrix();
    }

    /**
     * The panel, coloured exactly as the open box would be.
     *
     * Colourise uses a recoloured copy of the pack's texture, one per colour, built once and kept;
     * Multiply draws the pack's own texture through a tint, as the screen does. With the panel
     * tint off it is the pack's texture untouched.
     */
    private void drawPanel(DrawContext context, Look look, float alpha) {
        int a = Math.round(alpha * 255.0F) & 0xFF;
        Layout layout = layout();

        piece(context, look, 0, 0, 0, WIDTH, layout.topHeight(), a, 1.0F);
        if (layout.lidHeight() > 0) {
            piece(context, look, 0, layout.topHeight(), layout.lidV(), WIDTH, layout.lidHeight(), a, 1.0F);
        }

        int edgeY = layout.topHeight() + layout.lidHeight();
        int shade = bottomShade.get();
        if (shade <= 0) {
            piece(context, look, 0, edgeY, layout.bottomV(), WIDTH, BOTTOM_HEIGHT, a, 1.0F);
            return;
        }

        /*
         * The bevel, shaded from the box's own last row of panel rather than taken from below the
         * inventory. That row has the box's colour and its own side borders, so darkening it
         * gives a darker green with the outline still meeting the sides. The outline row under
         * it is still the pack's.
         */
        float factor = 1.0F - shade / 100.0F;
        for (int row = 0; row < BEVEL_HEIGHT; row++) {
            piece(context, look, 0, edgeY + row, layout.shadeV(), WIDTH, 1, a, factor);
        }
        int outline = BOTTOM_HEIGHT - BEVEL_HEIGHT;
        piece(context, look, 0, edgeY + BEVEL_HEIGHT, layout.bottomV() + BEVEL_HEIGHT, WIDTH, outline, a, 1.0F);
    }

    /**
     * How the panel is coloured this frame: which texture and colour above the split, where the
     * split is, and the untinted texture drawn below it.
     */
    private record Look(Identifier texture, int rgb, int split, Identifier plain) {}

    private Look look() {
        // Chests, barrels and the ender chest are drawn just as the pack has them.
        if (!shulker()) return new Look(CHEST_TEXTURE, 0xFFFFFF, 0, CHEST_TEXTURE);
        if (!tintBackground.get()) return new Look(TEXTURE, 0xFFFFFF, 0, TEXTURE);

        int rgb = shownColour == null ? packed(undyedColour.get()) : colourFor(shownColour);
        Identifier recoloured = blendMode.get() == BlendMode.Colourise ? tintedTexture(rgb) : null;

        if (recoloured != null) return new Look(recoloured, 0xFFFFFF, previewTintHeight.get(), TEXTURE);
        return new Look(TEXTURE, Colors.multiplyTint(rgb, tintStrength.get() / 100.0) & 0xFFFFFF, previewTintHeight.get(), TEXTURE);
    }

    /**
     * One rectangle of the panel, from texture row v down, tinted above the preview's tint height
     * and as the pack draws it below.
     *
     * The split is a row of the texture, not of the preview, so it reads exactly like the opened
     * box's tint height: anything up to 75 lands in the box, and the bottom edge only tints once
     * the height passes 163, where it sits in the real GUI. Brightness darkens the whole piece,
     * which is how the bottom edge gets its shade.
     */
    private void piece(DrawContext context, Look look, int x, int y, int v, int width, int height, int a, float brightness) {
        int tintedRows = Math.max(0, Math.min(height, look.split() - v));

        if (tintedRows > 0) {
            context.drawTexture(RenderPipelines.GUI_TEXTURED, look.texture(), x, y, (float) x, (float) v,
                width, tintedRows, 256, 256, argb(a, Colors.scale(look.rgb(), brightness)));
        }

        int rest = height - tintedRows;
        if (rest > 0) {
            context.drawTexture(RenderPipelines.GUI_TEXTURED, look.plain(), x, y + tintedRows, (float) x, (float) (v + tintedRows),
                width, rest, 256, 256, argb(a, Colors.scale(0xFFFFFF, brightness)));
        }
    }

    private static int argb(int a, int rgb) {
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    /**
     * Each filled slot drawn again over its item, at the given strength, which is how the items
     * fade: from the empty slot to the item as this goes from 1 to 0.
     *
     * Drawn after the items, so the game puts it on a layer above them. The 16 pixel square
     * where the item sits is all it needs to cover, but the item count can hang a pixel past
     * that, so the whole 18 pixel slot is covered.
     */
    private void coverItems(DrawContext context, Look look, float strength) {
        int a = Math.round(strength * 255.0F) & 0xFF;
        if (a == 0) return;

        for (int i = 0; i < shownBox.items().size(); i++) {
            if (shownBox.items().get(i).isEmpty()) continue;

            int x = 7 + (i % 9) * 18;
            int y = 17 + (i / 9) * 18;
            piece(context, look, x, y, y, 18, 18, a, 1.0F);
        }
    }

    /** The slots, where the real GUI puts them: 27 for most, 54 for a double chest. */
    private void drawItems(DrawContext context) {
        for (int i = 0; i < shownBox.items().size(); i++) {
            ItemStack stack = shownBox.items().get(i);
            if (stack.isEmpty()) continue;

            int x = 8 + (i % 9) * 18;
            int y = 18 + (i / 9) * 18;
            context.drawItem(stack, x, y);
            context.drawStackOverlay(mc.textRenderer, stack, x, y);
        }
    }

    /**
     * The box's name, in the same colour the open box's title would be.
     *
     * Shrunk evenly if it is wider than the row of slots, like a long name on the real screen.
     * Skipped entirely near the end of a fade: the text renderer treats a nearly invisible colour
     * as fully opaque, so drawing it there would flash the title at full strength.
     */
    private void drawTitle(DrawContext context, float alpha) {
        int a = Math.round(alpha * 255.0F) & 0xFF;
        if (a < 8 || shownTitle == null) return;

        int rgb = shownColour == null ? packed(undyedColour.get()) : colourFor(shownColour);
        Integer titleRgb = !shulker() ? null : switch (titleMode.get()) {
            case Vanilla -> null;
            case MatchBox -> Colors.adjustBrightness(rgb, titleBrightness.get() / 100.0);
            case InverseBrightness -> Colors.inverseLightness(rgb, inverseStrength.get() / 100.0);
            case Custom -> packed(customTitleColour.get());
        };
        int colour = (a << 24) | ((titleRgb == null ? VANILLA_TITLE : titleRgb) & 0xFFFFFF);

        int width = mc.textRenderer.getWidth(shownTitle);
        int limit = WIDTH - 16;

        if (width <= limit) {
            context.drawText(mc.textRenderer, shownTitle, 8, 6, colour, false);
            return;
        }

        float s = limit / (float) width;
        float drop = mc.textRenderer.fontHeight * (1.0F - s) * 0.5F;
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(8.0F, 6.0F + drop);
        context.getMatrices().scale(s, s);
        context.drawText(mc.textRenderer, shownTitle, 0, 0, colour, false);
        context.getMatrices().popMatrix();
    }

    /** A name across the top of the panel, shrunk to fit if it is too long, as titles are. */
    private void drawName(DrawContext context, String name, int colour) {
        int width = mc.textRenderer.getWidth(name);
        int limit = WIDTH - 16;
        if (width <= limit) {
            context.drawText(mc.textRenderer, name, 8, 6, colour, false);
            return;
        }

        float s = limit / (float) width;
        float drop = mc.textRenderer.fontHeight * (1.0F - s) * 0.5F;
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(8.0F, 6.0F + drop);
        context.getMatrices().scale(s, s);
        context.drawText(mc.textRenderer, name, 0, 0, colour, false);
        context.getMatrices().popMatrix();
    }

    // Storage tooltips

    /**
     * A shulker box or ender chest under the mouse in an inventory, drawn as its own GUI.
     *
     * A shulker box item carries its contents, so it is read straight off the item, coloured and
     * named as the box would be once placed and opened. An ender chest item carries nothing, so
     * it shows your ender chest as last seen on this server.
     *
     * Placed where a tooltip would go, beside the mouse and kept on screen, and on a layer of its
     * own so the inventory's items stay underneath it. The same drawing as the crosshair preview,
     * at the real GUI's size, with no animation: a tooltip has none either.
     */
    private boolean drawInventory(DrawContext context, ItemStack stack, int mouseX, int mouseY) {
        if (!(stack.getItem() instanceof BlockItem item)) return false;

        StorageMemory.Box box;
        DyeColor colour = null;
        String title;

        if (item.getBlock() instanceof ShulkerBoxBlock shulkerBlock) {
            if (!shulkerInventory.get()) return false;

            DefaultedList<ItemStack> slots = DefaultedList.ofSize(StorageMemory.SIZE, ItemStack.EMPTY);
            ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
            if (container != null) container.copyTo(slots);

            box = new StorageMemory.Box(null, new ArrayList<>(slots), StorageMemory.Kind.Shulker);
            colour = shulkerBlock.getColor();
            title = stack.getName().getString();
        }
        else if (item.getBlock() instanceof EnderChestBlock) {
            if (!enderInventory.get()) return false;

            box = StorageMemory.ender();
            if (box == null) return false;
            title = box.name() != null ? box.name() : Text.translatable("container.enderchest").getString();
        }
        else {
            return false;
        }

        // The drawing reads what is being shown from these, so the crosshair preview's are set
        // aside for the moment and put back after, whatever happens in between.
        StorageMemory.Box oldBox = shownBox;
        DyeColor oldColour = shownColour;
        String oldTitle = shownTitle;

        shownBox = box;
        shownColour = colour;
        shownTitle = title;

        try {
            Layout layout = layout();
            int width = WIDTH;
            int height = layout.height();
            int screenWidth = context.getScaledWindowWidth();
            int screenHeight = context.getScaledWindowHeight();

            int x = mouseX + 12;
            if (x + width > screenWidth - 4) x = mouseX - 12 - width;
            x = Math.max(4, x);

            int y = mouseY - 12;
            y = Math.max(4, Math.min(y, screenHeight - height - 4));

            context.createNewRootLayer();
            context.getMatrices().pushMatrix();
            context.getMatrices().translate((float) x, (float) y);

            drawPanel(context, look(), 1.0F);
            drawItems(context);

            if (box.kind() == StorageMemory.Kind.Ender) {
                // Drawn the same way, in the same layer, as a shulker box's name, which shows
                // reliably; the name sits above the slots, so nothing is drawn over it.
                if (enderTooltipName.get()) drawName(context, title, 0xFF000000 | packed(enderTooltipNameColour.get()));
            }
            else {
                drawTitle(context, 1.0F);
            }

            context.getMatrices().popMatrix();
        } finally {
            shownBox = oldBox;
            shownColour = oldColour;
            shownTitle = oldTitle;
        }

        return true;
    }

    // Item icons and fill bar

    /*
     * Summaries of what boxes hold, kept by the contents they were worked out from.
     *
     * A slot is drawn every frame and the contents of an item never change in place: a changed
     * box is a new component. So the answer for a given component is worked out once and looked
     * up after, by identity, which is free. Cleared when it gets large, since old components are
     * never asked about again.
     */
    private final Map<Object, StorageSummary.Summary> summaries = new IdentityHashMap<>();
    private boolean drawingExtras;

    private void drawSlotExtras(DrawContext context, ItemStack stack, int x, int y) {
        if (drawingExtras || !(stack.getItem() instanceof BlockItem item)) return;

        boolean icons;
        boolean bar;
        List<ItemStack> slots;
        String name = null;
        Object key;

        if (item.getBlock() instanceof ShulkerBoxBlock) {
            icons = shulkerIcons.get();
            bar = shulkerFillBar.get();
            ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
            key = container == null ? ContainerComponent.DEFAULT : container;

            Text custom = stack.get(DataComponentTypes.CUSTOM_NAME);
            if (custom != null) name = custom.getString();

            slots = null;
        }
        else if (item.getBlock() instanceof EnderChestBlock) {
            icons = enderIcons.get();
            bar = enderFillBar.get();
            StorageMemory.Box ender = StorageMemory.ender();
            if (ender == null) return;
            key = ender;
            slots = ender.items();
        }
        else {
            return;
        }

        if (!icons && !bar) return;

        StorageSummary.Summary summary = summaries.get(key);
        if (summary == null) {
            if (slots == null) {
                DefaultedList<ItemStack> list = DefaultedList.ofSize(StorageMemory.SIZE, ItemStack.EMPTY);
                ((ContainerComponent) key).copyTo(list);
                slots = list;
            }
            if (summaries.size() > 512) summaries.clear();
            summary = StorageSummary.of(slots, StorageMemory.SIZE, grouping.get(), fillMeasure.get());
            summaries.put(key, summary);
        }

        drawingExtras = true;
        try {
            // Whichever is drawn second goes on top: the game layers a later element over an
            // earlier one wherever the two overlap.
            if (iconBehindBar.get()) {
                if (icons) drawIcon(context, summary, name, stack.getCount() > 1, x, y);
                if (bar) drawFillBar(context, summary, x, y);
            }
            else {
                if (bar) drawFillBar(context, summary, x, y);
                if (icons) drawIcon(context, summary, name, stack.getCount() > 1, x, y);
            }
        } finally {
            drawingExtras = false;
        }
    }

    private boolean anyFillBar() {
        return shulkerFillBar.get() || enderFillBar.get();
    }

    /** Settings that change what a summary says mean the kept ones are stale. */
    private StorageSummary.Grouping lastGrouping;
    private StorageSummary.Fill lastMeasure;

    private void checkSummaries() {
        if (grouping.get() != lastGrouping || fillMeasure.get() != lastMeasure) {
            summaries.clear();
            lastGrouping = grouping.get();
            lastMeasure = fillMeasure.get();
        }
    }

    private void drawIcon(DrawContext context, StorageSummary.Summary summary, String name, boolean stacked, int x, int y) {
        StorageSummary.Group group = chooseIcon(summary, name);
        if (group == null || group.total() < minCount.get()) return;

        boolean moved = stacked && moveForCount.get();
        int ox = moved ? stackedIconX.get() : iconX.get();
        // Up is positive, as on a graph, while the screen counts down.
        int oy = -(moved ? stackedIconY.get() : iconY.get());
        int size = iconSize.get();

        /*
         * Sized about the icon's own centre: its corner is put half its size up and left of where
         * the centre should be. Rounded to a whole pixel, so an odd size does not land the icon
         * between pixels and blur it.
         */
        float drawn = size;
        float left;
        float top;

        if (pixelPerfect.get()) {
            /*
             * Worked in screen pixels, where the evenness actually has to hold. The art is 16
             * pixels across, so it is even when it covers a whole multiple of 16 screen pixels:
             * each pixel of art then becomes that many screen pixels, every one the same. The
             * nearest such size is used, never less than one screen pixel per pixel of art, and
             * the corner is put on a whole screen pixel so no row or column is split.
             */
            float gui = (float) mc.getWindow().getScaleFactor();
            int perPixel = Math.max(1, Math.round(size * gui / 16.0F));
            int screenSize = perPixel * 16;
            drawn = screenSize / gui;

            float centreX = (x + 8.0F + ox) * gui;
            float centreY = (y + 8.0F + oy) * gui;
            left = Math.round(centreX - screenSize / 2.0F) / gui;
            top = Math.round(centreY - screenSize / 2.0F) / gui;
        }
        else {
            left = x + Math.round(8.0F + ox - size / 2.0F);
            top = y + Math.round(8.0F + oy - size / 2.0F);
        }

        float scale = drawn / 16.0F;

        context.getMatrices().pushMatrix();
        context.getMatrices().translate(left, top);
        context.getMatrices().scale(scale, scale);
        context.drawItem(group.icon(), 0, 0);
        context.getMatrices().popMatrix();
    }

    private StorageSummary.Group chooseIcon(StorageSummary.Summary summary, String name) {
        List<StorageSummary.Group> groups = summary.groups();
        if (groups.isEmpty()) return null;

        if (nameUse.get() != NameUse.Ignore) {
            StorageSummary.Group named = StorageSummary.byName(groups, name);
            if (named != null) return named;
            if (nameUse.get() == NameUse.Only) return null;
        }

        return switch (iconChoice.get()) {
            case First -> groups.stream().min(java.util.Comparator.comparingInt(StorageSummary.Group::first)).orElse(null);
            case Last -> groups.stream().max(java.util.Comparator.comparingInt(StorageSummary.Group::last)).orElse(null);
            case Most -> groups.stream().max(java.util.Comparator.comparingInt(StorageSummary.Group::total)).orElse(null);
            case Least -> groups.stream().min(java.util.Comparator.comparingInt(StorageSummary.Group::total)).orElse(null);
            case OnlyIfOneKind -> groups.size() == 1 ? groups.get(0) : null;
        };
    }

    private void drawFillBar(DrawContext context, StorageSummary.Summary summary, int x, int y) {
        double fill = summary.fill();
        if (summary.empty() && hideEmpty.get()) return;
        if (fill >= 1.0 && hideFull.get()) return;

        int length = barLength.get();
        int thick = barThickness.get();
        int filled = summary.empty() ? 0 : Math.max(1, (int) Math.round(fill * length));

        boolean across = barDirection.get() == BarDirection.LeftToRight || barDirection.get() == BarDirection.RightToLeft;
        int left = x + barX.get();
        int top = y + barY.get();
        int width = across ? length : thick;
        int height = across ? thick : length;

        if (barBackground.get()) {
            // One pixel of border under and beside it, as a durability bar has.
            int bg = argb(255, packed(backgroundColour.get()));
            context.fill(left, top, left + width + (across ? 0 : 1), top + height + (across ? 1 : 0), bg);
        }

        int colour = argb(255, packed(fill >= 1.0 ? fullColour.get() : barColour.get()));
        switch (barDirection.get()) {
            case LeftToRight -> context.fill(left, top, left + filled, top + thick, colour);
            case RightToLeft -> context.fill(left + length - filled, top, left + length, top + thick, colour);
            case TopToBottom -> context.fill(left, top, left + thick, top + filled, colour);
            case BottomToTop -> context.fill(left, top + length - filled, left + thick, top + length, colour);
        }
    }

    private Identifier tintedTexture(int rgb) {
        int strength = tintStrength.get();
        long key = ((long) (rgb & 0xFFFFFF) << 8) | (strength & 0xFF);

        Identifier known = tinted.get(key);
        if (known != null) return known;

        Identifier target = Identifier.of("jjs-tools",
            "gui/shulker_preview_" + Integer.toHexString(rgb & 0xFFFFFF) + "_" + strength);
        if (!TintedTexture.buildInto(TEXTURE, target, rgb, strength / 100.0, tintHeight.get())) return null;

        tinted.put(key, target);
        return target;
    }

    /** Lets go of the recoloured textures, so a changed resource pack is picked up next time. */
    private void forgetTextures() {
        if (mc != null && mc.getTextureManager() != null) {
            for (Identifier id : tinted.values()) mc.getTextureManager().destroyTexture(id);
        }
        tinted.clear();
    }

    private static SettingColor rgb(int rgb) {
        return new SettingColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    private static int packed(SettingColor colour) {
        return (colour.r << 16) | (colour.g << 8) | colour.b;
    }

    // Read by ShulkerBoxScreenMixin and HandledScreenTitleMixin

    private static StorageGuiTweaks active() {
        StorageGuiTweaks module = Modules.get().get(StorageGuiTweaks.class);
        return module != null && module.isActive() ? module : null;
    }

    public static boolean on() {
        return active() != null;
    }

    public static boolean backgroundTinted() {
        StorageGuiTweaks m = active();
        return m != null && m.tintBackground.get();
    }

    public static boolean colorizing() {
        StorageGuiTweaks m = active();
        return m != null && m.blendMode.get() == BlendMode.Colourise;
    }

    public static int strength() {
        StorageGuiTweaks m = active();
        return m == null ? 100 : m.tintStrength.get();
    }

    public static int splitY() {
        StorageGuiTweaks m = active();
        return m == null ? 78 : m.tintHeight.get();
    }

    public static TitleMode titleMode() {
        StorageGuiTweaks m = active();
        return m == null ? TitleMode.Vanilla : m.titleMode.get();
    }

    public static int titleBrightness() {
        StorageGuiTweaks m = active();
        return m == null ? 60 : m.titleBrightness.get();
    }

    public static int inverseStrength() {
        StorageGuiTweaks m = active();
        return m == null ? 60 : m.inverseStrength.get();
    }

    public static int customTitleColour() {
        StorageGuiTweaks m = active();
        return m == null ? 0xFFFFFF : packed(m.customTitleColour.get());
    }

    /**
     * How wide a box's name is allowed to be, or 0 to leave it alone.
     *
     * Only the budget is decided here. How the name is made to fit it is the drawing code's
     * business, and the two answers to that differ in more than a number.
     */
    public static int titleLimit(ScreenHandler handler, int titleX) {
        StorageGuiTweaks m = active();
        if (m == null || !m.shrinkTitle.get()) return 0;

        int limit = m.fitToSlots.get() ? slotSpan(handler, titleX) : m.titleWidth.get();
        return Math.max(limit, 0);
    }

    /** Draws the icon and fill bar on a shulker box or ender chest item in a slot, if there is one to draw. */
    public static void slotExtras(DrawContext context, ItemStack stack, int x, int y) {
        StorageGuiTweaks m = active();
        if (m != null && stack != null && !stack.isEmpty()) m.drawSlotExtras(context, stack, x, y);
    }

    /**
     * Draws the storage tooltip for a hovered item in place of its tooltip, if it is one this
     * does. Returns false to let the tooltip draw as normal.
     */
    public static boolean inventoryPreview(DrawContext context, ItemStack stack, int mouseX, int mouseY) {
        StorageGuiTweaks m = active();
        return m != null && stack != null && !stack.isEmpty() && m.drawInventory(context, stack, mouseX, mouseY);
    }

    /** Whether chest-style screens get their names fitted: any of chests, barrels or ender chests turned on. */
    public static boolean fitsChestTitles() {
        StorageGuiTweaks m = active();
        return m != null && (m.chests.get() || m.barrels.get() || m.enderChests.get());
    }

    public static TitleFit titleFit() {
        StorageGuiTweaks m = active();
        return m == null ? TitleFit.Spaces : m.titleFit.get();
    }

    /**
     * From where the title starts to the right edge of the last slot in the box.
     *
     * Read off the slots rather than written down, so it is right whatever the container is and
     * cannot drift if a width changes. Player inventory slots are skipped: they are wider than the
     * box's own row and would let the name overhang it.
     */
    private static int slotSpan(ScreenHandler handler, int titleX) {
        if (handler == null) return 0;

        int right = Integer.MIN_VALUE;
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory) continue;
            if (slot.x + 16 > right) right = slot.x + 16;
        }

        return right == Integer.MIN_VALUE ? 0 : right - titleX;
    }

    public static boolean overrideNameColours() {
        StorageGuiTweaks m = active();
        return m != null && m.overrideNameColours.get();
    }

    public static int colourFor(DyeColor dye) {
        StorageGuiTweaks m = active();
        if (m == null) return Colors.defaultDyeRgb(dye);

        Setting<SettingColor> setting = m.dyeColours.get(dye);
        return setting == null ? Colors.defaultDyeRgb(dye) : packed(setting.get());
    }

    public static int undyedColour() {
        StorageGuiTweaks m = active();
        return m == null ? Colors.DEFAULT_UNDYED : packed(m.undyedColour.get());
    }
}
