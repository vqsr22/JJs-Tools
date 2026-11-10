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
| Minecraft | 1.21.4, 1.21.5, 1.21.8 or 1.21.11, with the matching jar |
| Loader | [Fabric][fabric] |
| Required | [Meteor Client][meteor] |
| Optional | [XaeroPlus][xaeroplus], for the visited-chunk filter |
| Optional | [ViaFabricPlus][vfp], if you connect on an older protocol |


## Modules

| Module | Description |
|---|---|
| **Ad Blocker** | Hides advertisers in chat |
| **Advanced Autolog** | Logs out on low health, totem pops, nearby players, stasis pulls, set coordinates and more |
| **Anti Item Frame** | Stops accidental item frame clicks and can open the chest behind instead |
| **Anti Rocket Place** | Rockets boost you instead of being placed |
| **Auto Door** | Opens doors ahead of you and closes them behind you |
| **Auto Enchant** | Applies your enchanted books to chosen gear at an anvil |
| **Auto Mason** | Works the stonecutter for you |
| **Auto Omen** | Drinks ominous bottles |
| **Auto Torch** | Lights an area with as few torches as possible |
| **Auto Turtle Helmet** | Puts on a turtle helmet from your inventory |
| **Auto Wear Gold** | Wears a gold piece while piglins are near |
| **Bed Marker** | Marks your last spawn bed |
| **Better Hitreg** | Makes hits feel instant. Ported from [Better Hitreg][betterhitreg] |
| **Chat Notifications** | Plays a sound on whispers and mentions |
| **Chat Tweaks** | Cleans up green text, prefixes and client junk in chat |
| **Control Panel** | A monkeytype-style screen with chat, stashes, encounters, pearlbots, player lookup, deaths, session stats and your ender chest, 187 themes and custom fonts. Also adds JJ's theme and Themes and Fonts tabs to Meteor's GUI |
| **Custom Trims** | Client side armour trims on your own armour |
| **Elytra Takeoff** | Takes off with an elytra and rocket on one key |
| **Farm Aura** | Tills and plants farmland around you |
| **Fast Rename** | Renames items in bulk at an anvil |
| **Friend Sync** | Copies your Meteor friends into other clients by running their friend commands for you |
| **Grinder** | Strips enchantments at the grindstone |
| **Incognito** | Covers your coordinates and minimap |
| **Inventory Loadout** | Saves your inventory layout and restores it on a key |
| **Kit Creator** | Copies a shulker box layout into another one |
| **Light Levels** | Shows where mobs can spawn, with a per-mob filter |
| **Module Wheel** | Hold a key and let go over a module to toggle it or a command to run it, each with its own name and icon. Right-click and drag to rearrange |
| **No Jolt** | Stops the health and hunger icons shaking |
| **Optimise Tablist** | Makes the tab list cheap to draw, with an optional JJ's theme look |
| **Pickup Loot Stream** | Shows what you pick up in a corner of the screen |
| **Portal Print Detector** | Finds where nether portals used to be |
| **Portal Scraps** | Finds leftover obsidian in the Nether |
| **Printer Go Brrr** | Builds Litematica schematics as you walk past. Needs Litematica |
| **Rare Item Highlighter** | Highlights rare items in containers |
| **Rubberband Notifier** | Reports lagbacks |
| **Shulker Dyer** | Dyes shulker boxes and bundles in crafting grids |
| **Sign to Item Frame** | Shows the item a sign names on the sign |
| **Storage GUI Tweaks** | Previews container contents at your crosshair and in tooltips, and tints shulker GUIs |
| **Team Trees** | Plants saplings |
| **Unobtrusive** | Moves or hides overlays and hand animations in the middle of the screen |
| **Via Enchant Fix** | Fixes enchantment tooltips on ViaFabricPlus |
| **Via Recipe Fix** | Fixes the recipe book on ViaFabricPlus |
| **Via Stonecutter Fix** | Fixes stonecutter buttons on ViaFabricPlus |
| **Via Texture Fix** | Fixes item names and models on ViaBackwards |
| **XCarry** | Uses the crafting grid as extra inventory slots |


## Extra tabs

- **Icons**: set any category's icon, with optional glint.
- **Conflicts**: rename modules from different add-ons that share a name.
- **Themes** and **Fonts**: pick the theme and font for the Control Panel and Meteor's GUI.

## HUD elements

**Custom Text** and **Custom Shape** (rectangle, ellipse, triangle or line).

## Borderless windowed

Video Settings gets a **Borderless Windowed** option for instant alt-tabbing.

## Config saving

`auto-save-config` in Meteor's Config tab saves your config every five minutes, so a crash does not lose your changes.

## Where your data is

Your data is kept in `.minecraft/jjs-tools/`, mostly per account, so Meteor updates and profile wipes do not touch it.

## Building

Requires JDK 21. The Gradle wrapper is included.

One codebase builds for every supported Minecraft version. If planning to build from the
source, use the selected version available to build:

```
.\gradlew.bat build "-Pminecraft_version=1.21.4"
.\gradlew.bat build "-Pminecraft_version=1.21.5"
.\gradlew.bat build "-Pminecraft_version=1.21.8"
.\gradlew.bat build "-Pminecraft_version=1.21.11"
```

On Linux or macOS, use `./gradlew build -Pminecraft_version=1.21.4` and so on. With no
version given it builds for 1.21.11.

The jar is in `build/libs/`, named for its version, for example `jjs-tools-0.5.3+1.21.4.jar`
(use the one without `-sources`). Each jar only runs on the Minecraft version in its name.

### How the versions are kept in one codebase

- `versions/<version>.properties` holds the mappings and Meteor build for each version.
- Code that differs between versions is written in `//#if` blocks, for example:

  ```java
  //#if MC >= 1.21.9
  return entity.getEntityPos();
  //#else
  //$$ return entity.getPos();
  //#endif
  ```

  Before compiling, `gradle/versioned.gradle` copies `src/main` to `build/versioned/<version>`,
  keeps the branch for the version being built and uncomments its `//$$` lines. The 1.21.11
  branch is written as plain code, so `src/main` reads as ordinary 1.21.11 code in an IDE.
- Small calls that were only renamed between versions go through `dev.jjstools.compat.Compat`
  (game and Meteor calls) and `dev.jjstools.compat.Gui` (GUI drawing), so most files need no
  version blocks at all.

To add a version, add its `versions/<version>.properties` and build it; the compiler errors
show what needs a version block.

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
