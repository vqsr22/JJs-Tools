<div align="center">

<img src="src/main/resources/assets/jjs-tools/icon.png" alt="JJ's Tools" width="160">

<h2>JJ's Tools</h2>

<p><i>A Meteor Client add-on for 2b2t, built around quality of life, automation and hunting.</i></p>

</div>

---

## Overview

JJ's Tools is a [Meteor Client][meteor] add-on specifically built for 2b2t.

Credit to lambda and stardust for some modules

## Requirements

| Requirement | Description |
|---|---|
| Minecraft | 1.21.11 |
| Loader | [Fabric][fabric] |
| Required | [Meteor Client][meteor] |
| Optional | [XaeroPlus][xaeroplus], for the visited-chunk filter |
| Optional | [ViaFabricPlus][vfp], if you connect on an older protocol |


## Modules

| Module | Description |
|---|---|
| **Ad Blocker** | Hides advertisers in chat |
| **Advanced Autolog** | Logging tools for AFK travel: health, totem pops, crystals placed next to you, a player coming near, being pulled by a stasis chamber, reaching a set of coordinates, standing still, server restarts, and clicking a trapdoor or lever before it drops you |
| **Anti Item Frame** | Stops you rotating or interacting with item frames by accident, and can hand the click to the chest or barrel the frame is labelling |
| **Anti Rocket Place** | Makes rockets boost you instead of firing at whatever block you are looking at |
| **Auto Door** | Opens doors ahead of you and shuts them behind you. Leaves a door alone entirely when there is a pressure plate at it, rather than fighting the plate |
| **Auto Enchant** | Puts your enchanted books onto your gear at an anvil, book after book, until there is nothing left to add. Pick the items and the enchantments you want; the anvil decides what fits and a book it refuses is set aside rather than stalling the run |
| **Auto Mason** | Works the stonecutter for you |
| **Auto Omen** | Drinks ominous bottles |
| **Auto Torch** | Lights an area with as few torches as possible, rather than one every few blocks. Can keep to your feet level only, and raycasts so it never places through walls |
| **Auto Turtle Helmet** | Puts a turtle helmet on from your inventory |
| **Auto Wear Gold** | Puts a gold piece on while piglins are near and your own piece back after. Any of the four slots, leggings by default. Keeps the gold on rather than leaving the slot bare if you had nothing there to begin with |
| **Bed Marker** | Marks the bed you last set your spawn at, kept across sessions. Clears itself if the bed is broken in front of you |
| **Better Hitreg** | Makes hits feel instant. Plays the flinch, the sound and the crit and sharpness particles on the click instead of a round trip later, and swallows the server's copy when it arrives. Does not change the hit itself, and does not improve latency. Ported from [Better Hitreg][betterhitreg] |
| **Chat Notifications** | Plays a sound on whispers and mentions |
| **Chat Tweaks** | Cleans up chat: green text, the arrow prefix, and the junk other clients append, including bare random tokens. Can hide those messages outright instead |
| **Control Panel** | One clean, minimal screen of its own inspired by monkeytype, with soft rounded corners and hover colours that swap like monkeytype's, with all 187 of monkeytype's themes in a Themes box like monkeytype's, plus Dark and Light from your resource pack and an RGB theme whose colours cycle, with panels you can open, close, move and resize, a dock at the top or bottom of the screen, a text size and GUI scale of its own, a font page like monkeytype's (Roboto Mono built in, 37 more open-licensed fonts downloaded the first time you pick them, or Meteor's or Minecraft's font), your own accent and main colours, and a Hide coords switch for streams. **Chat**: every whisper kept per person like a messenger, with timestamps in your time zone, people you pin (hover a name for the pin) at the very top, then friends, with whoever is online first, online lights, sort by name or recent either way, and columns you can drag wider or narrower. **Stash Manager**: every container opened within a stash's radius (5000 by default) counted as one big inventory, shulker contents included and enchanted items kept apart by their enchantments, with raw, stack, shulker and dub (54 shulker) counts, creative tab filters, search and sort, outlines in the world, and Baritone walking to the best container and opening it. A container you see broken is dropped from the stash, and the stash shares what it knows with Storage GUI Tweaks' previews both ways. Stashes can stay on your computer, sync through a shared folder, or sync through a small server of your own on a VPS (see `server/README.md`). **Encounters**, always on, recording every player you meet with first and last sighting, count and coordinates (its settings are in the Control Panel module's Encounters group), your 2b2t **Blocked** list, a **Death Log** with coordinates and cause, **Session Stats**, your **Ender Chest** as last seen, with hover tooltips (needs Storage GUI Tweaks), **Pearls**, your pearlbots by base: pull whispers the pearlbot, then the stasis chamber you land at is found from where the teleport puts you (so bases with many chambers work) and reset: you walk onto dry ground beside it (across from its trapdoor where there is room), flip the trapdoor back if needed, crouch-walk to the edge and throw a pearl straight down into the water, with Baritone fetching a single pearl from the base's saved container first if you have none; if a pearl is thrown to you while your inventory is full, the item in your ninth slot is dropped onto the chamber's trapdoor to make room and picked back up afterwards, and whatever the pearl moved out of your ninth slot goes back there; you can look around freely the whole time (Meteor's Free Look in camera mode, put back afterwards, leaving you looking where you were); its options are in the Pearls group, and **Lookup**, a player's 2b2t stats from 2b2t.vc: first and last seen, playtime, kills, deaths, K/D, joins, chats and priority queue. Typing in any of its text boxes stops you moving, GUI Move included. Opened with the Open GUI Key setting. Also adds **JJ's theme** to Meteor's GUI tab: Meteor's GUI drawn like the Control Panel and monkeytype's settings page, with the same rounded cards, button shades, instant colour-swap on hover, pill sliders, dim headings, lowercase names and font, following whichever theme and font you pick there, a search bar under the top bar, module columns you can resize by dragging their bottom edge, a module list scale of its own for the module columns, an auto fit button that sizes them to fill the screen, and column stacking to put any column under another, as deep as you like (adapted from Meteor's own theme, GPL-3.0), and **Themes** and **Fonts** tabs to Meteor's GUI that pick the theme and font for the Control Panel and Meteor's GUI together |
| **Custom Trims** | Client side armour trims on your own armour, worn or carried, visible only to you |
| **Elytra Takeoff** | Jump, open the elytra and fire a rocket on one key, or deploy it on a double tap of jump with or without a rocket, swapping your chestplate out on takeoff and back on landing |
| **Farm Aura** | Tills dirt and grass in reach into farmland with a hoe from your inventory, stopping before it breaks, and plants a chosen crop on every empty bit of farmland |
| **Fast Rename** | Renames items in bulk at an anvil, opening the replacement when one breaks under you. Running out of levels pauses it rather than ending it: the anvil is reopened and renaming carries on once the experience is back |
| **Grinder** | Strips enchantments off selected items at the grindstone |
| **Incognito** | Covers your coordinates and minimap for screenshots and streams |
| **Inventory Loadout** | Saves where everything in your inventory, armour and offhand sits and puts it back on a key. Tells a silk tool from a fortune one and ignores every other enchantment |
| **Kit Creator** | Scans a shulker box, then lays another one out exactly the same. Rearranges what is already inside, fills the rest from your inventory and shifts out anything that does not belong. Matches by type and enchantments, ignoring names |
| **Light Levels** | Improved from meteor's. Where mobs can spawn, as clean coloured squares, with a per-mob filter so you can see what a creeper, spider, zombie, skeleton or enderman would actually fit in, a spawn area filter per dimension, and mushroom islands left out since nothing hostile spawns there |
| **Module Wheel** | Hold a key to open a wheel of the modules you pick, in the Control Panel's colours, and let go over one to turn it on or off. Letting go in the circle in the middle changes nothing. Right-click and drag a module to snap it into another slot, and give each one any item as its icon, enchanted or not |
| **No Jolt** | Holds the health and hunger icons still instead of letting vanilla shake them at you |
| **Optimise Tablist** | Makes the tab list cheap to draw on servers with huge player counts. Its lite renderer can draw the list in JJ's theme: one clean rounded panel in the Control Panel's colours, names in plain text colour, friends in Meteor's friend colour and no ping bars. Friends are coloured whether or not Better Tab is on, and the list can use the font you pick in the Fonts tab |
| **Pickup Loot Stream** | Shows what you have just picked up as a list of rows in a corner of the screen, bottom right by default. Rows slide and fade in and out, glide up into gaps, merge repeat pickups with a pulse on the count, and nearly everything is adjustable: corner, offsets, scale, font (including resource pack fonts), text size and colours, icon side and size, count format and place, name, inventory total, background, rounded corners, accent strip, timing and easing |
| **Portal Print Detector** | Finds the footprint a removed nether portal leaves behind: a four long recessed slot, or a patch of bare netherrack sitting in nylium |
| **Portal Scraps** | Finds leftover obsidian in the Nether. Skips ruined portals and, optionally, highways and ring roads |
| **Printer Go Brrr** | Builds your Litematica schematics as you walk past them, wherever they are placed. Air places by default (fine on 2b2t), in bursts of up to 8 clicks with a short wait between, putting each kind of block in hand once and placing every spot in reach that wants it. Each burst turns once, the way a player turns, and places everything that comes out right facing that way; blocks that need another facing wait their turn. Asks each item what it would place, so stairs, slabs, trapdoors, torches, signs and banners land right first time, and finishes double slabs. Only clicks faces you could see, exactly on the face and inset from its edges. Brings blocks in from your inventory one at a time into a few spare hotbar slots, and takes them straight from the creative menu in creative. Clicks trapdoors, doors, fence gates, levers, repeaters, comparators, daylight detectors and note blocks into the state the schematic has, and gets door hinges on the right side. Skips unobtainable blocks like spawners and end portal frames, explains itself in chat with debug on, and starts over by itself if it ever stalls. Fills in sign text, and in creative takes banners and other blocks with their data. Optional place sounds at any volume. An ESP that works even with the printer off, out to 64 blocks or more: white outlines on what is still to place and red ones on blocks placed wrong, and optionally on stray blocks where the schematic has air, through walls and in each block's real shape, so slabs show as slabs. Needs Litematica |
| **Rare Item Highlighter** | Highlights rare, unique and anomalous items in container slots |
| **Rubberband Notifier** | Reports lagbacks, with how many ticks you lost and how far you were moved. Ignores portals, pearls and joining |
| **Shulker Dyer** | Dyes shulker boxes and bundles in crafting grids |
| **Sign to Item Frame** | Draws the item a sign names on the face of the sign itself, so a wall of labelled signs reads as a wall of items. Write `Netherite Sword`, or add `:enchanted` for the glint, `:trim:gold` for an armour trim and `:name:Rainbow` to pick a resource pack variant, across as many lines as the name needs. Takes Grey or Gray and Armour or Armor. Draws a real item frame around it, plain or glowing, and takes the sign away entirely, board and all, or just blanks its writing. Client side only. The sign's hitbox can be taken away so your crosshair, clicks and crosshair previews go straight through to the chest behind. **If you use Better Block Entities:** its sign optimisation cannot work with this, so it is switched off automatically while Sign to Item Frame is on and back on when you turn it off |
| **Storage GUI Tweaks** | All your storage customisation needs! Shows what is inside a placed shulker box, chest, trapped chest, barrel or ender chest when you look at it, drawn with your resource pack's own GUI for it, a shulker in the box's colour, with fade or slide animations that fade the items too, easing, position, size and a shaded 3D bottom edge. Works from containers you have opened and shulker boxes you placed yourself, since the server never sends a placed container's contents, and remembers them per server across sessions. A double chest shows from either half, and your ender chest from any ender chest. Storage tooltips: hovering a shulker box or ender chest in an inventory shows it the same way, as the open GUI with its name at the top, in place of the tooltip. Shulker box and ender chest items also get a small icon of what is inside and a fill bar, both with their own position, size, colours and rules for which item is shown. Also colours the shulker GUI and its title to match the box, and fits a name too long for the panel without distorting its pixels |
| **Team Trees** | Plants saplings, either replanting a fixed plot you give coordinates for or scattering them across the ground around you. Presets cover every species, 1x1 and 2x2 |
| **Unobtrusive** | Moves or hides what the game draws in the middle of your screen: lowers the fire overlay and held shields, limits how far a held item dips after a swing and when switching items, each with its own distance and timing, removes the pumpkin and portal overlays, firework sparks and your own potion swirls, hides experience bottles and other thrown items while they are at the camera, holds food level in the middle of the screen while you eat, and slows and shrinks the bob while eating or drinking |
| **Via Enchant Fix** | Restores enchantment tooltip colours and ordering broken by ViaFabricPlus |
| **Via Recipe Fix** | Stops the recipe book ticking recipes as craftable when you do not have the ingredients, a ViaFabricPlus bug. Checks each recipe's own ingredient slots against what you are carrying, reading item tags from the game's vanilla data when the server's copy comes through empty, and only ever takes away a tick that should not be there |
| **Via Stonecutter Fix** | Fixes stonecutter buttons giving you the block next to the one you clicked on ViaFabricPlus. The server counts the buttons in recipe id order and the translated list is sorted by name, which disagree wherever one name starts another, so the buttons are put back in the server's order. Auto Mason picks from the same corrected list |
| **Via Texture Fix** | Repairs the names and models of items ViaBackwards had to translate down to an older protocol |
| **XCarry** | Keeps the crafting grid as four extra inventory slots |


## Extra tabs

Two tabs are added to the Meteor GUI. The Encounters list that used to be a tab here is now a
panel in the Control Panel.

**Icons** sets the category icon for every module category, including categories belonging to other
add-ons, with an optional enchantment glint.

**Conflicts** lists modules from different add-ons that share a name, and lets you rename either
side so both are usable. Meteor goes by name, so a clash means one keybind, one command and one
settings key between the two of them.

## HUD elements

Two elements are added to Meteor's HUD editor, under JJ's Tools. **Custom Text** shows any text
you type. **Custom Shape** draws a rectangle (with optional rounded corners), an ellipse, a
triangle pointing any way or a line at any angle, filled, outlined or both, in any colours.

## Borderless windowed

Video Settings gets a **Borderless Windowed** option in its Display section. The window loses its border and fills the monitor it is on, so it looks like fullscreen but alt-tabbing is instant. Real fullscreen still works while it is on, and leaving it comes back to borderless. The choice is kept in `.minecraft/jjs-tools/borderless.txt`.

## Config saving

Meteor only writes its config when you leave a world or shut the game down cleanly, so a crash
or a killed process loses everything changed since you joined. Meteor's own **Config** tab now
has a **JJ's Tools** group with `auto-save-config`, on by default, which writes it every five
minutes instead.

## Where your data is

Anything meant to stay after an update is stored outside Meteor's config, in `.minecraft/jjs-tools/`:

```text
.minecraft/jjs-tools/
├── accounts/
│   └── <your-uuid>/
│       ├── encounters.json    Players you have met
│       ├── bed-marker.json    Your last bed respawn point
│       ├── kit.json           The shulker layout Kit Creator last scanned
│       ├── storage-previews.json  What is inside the containers you have opened or placed
│       └── loadout.json       Where Inventory Loadout last saw your inventory
├── category-icons.json        Custom category icons
└── module-renames.json        Modules you renamed to settle a name clash
```

Encounters, your bed, your scanned kit and remembered container contents are kept per account, so two accounts on one install do
not overwrite each other. Category icons and module renames are shared, since they follow the
add-ons you have installed rather than who is signed in.

Updating Meteor, updating this add-on, or wiping a Meteor profile does not change these.

## Building

Requires JDK 21. The Gradle wrapper is included.

```bash
cd jjs-tools
./gradlew build
```

On Windows, `gradlew.bat build`.

The finished jar lands in `build/libs/`. Take the one without a `-sources` or `-dev` suffix.

Versions in `gradle.properties` follow Meteor's own 1.21.11 branch. Keep them matched to whichever
Meteor build you are running.

## Licence

GPL-3.0. See [LICENSE](LICENSE).

Parts are adapted from other projects, with attribution in the source files that use them.
Better Hitreg is Apache-2.0, which GPL-3.0 permits including; the rest is GPL-3.0.

[betterhitreg]: https://github.com/jasspir/BetterHitreg
[meteor]: https://meteorclient.com
[fabric]: https://fabricmc.net
[xaeroplus]: https://github.com/rfresh2/XaeroPlus
[vfp]: https://github.com/ViaVersion/ViaFabricPlus

## Fonts

Roboto Mono, the Control Panel's default font, is included under the SIL Open Font License; its licence is in `src/main/resources/assets/jjs-tools/fonts/RobotoMono-OFL.txt`. The other fonts on the font page are not included: each is downloaded from its own open-source repository when you pick it, and keeps its own licence.
