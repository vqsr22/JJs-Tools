<div align="center">

<img src="src/main/resources/assets/jjs-tools/icon.png" alt="JJ's Tools" width="160">

<h2>JJ's Tools</h2>

## Overview

JJ's Tools is a [Meteor Client][meteor] add-on specifically built for 2b2t.

It is very important to use the mod "ViaFabricPlus" and select version 1.20.5/1.20.6 red protocol for silent rotations to work properly

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
| **AdBlocker** | Hides advertisers in chat |
| **Advanced Autolog** | Auto logging for AFK travel over long distances |
| **Anti Item Frame** | Stops you rotating or interacting with item frames by accident |
| **Auto Door** | Opens doors ahead of you and shuts them behind you |
| **Auto Mason** | Automates stonecutter actions |
| **Auto Omen** | Drinks ominous bottles with advanced options for specific farm types |
| **Auto Smith** | Upgrades gear and trims armour at smithing tables |
| **Auto Turtle Helmet** | Puts a turtle helmet on from your inventory when in water |
| **Auto Wear Gold** | Puts a gold piece on while piglins are near and your own piece back after |
| **Banner Data** | Banner pattern information |
| **Battle Cry** | Sounds a goat horn when a player enters render distance |
| **Bed Marker** | Marks the bed you last set your spawn at, kept across sessions |
| **Chat Notifications** | Plays a sound on whispers and mentions |
| **Chat Tweaks** | Cleans up chat: green text, the arrow prefix, and the junk other clients append |
| **Custom Trims** | Client side armour trims on your own armour |
| **Elytra Takeoff** | Jumps and opens the elytra and then fires a rocket |
| **Enchant Fix** | Restores enchantment tooltip colours and ordering broken by ViaFabricPlus |
| **Encounters** | Records every player you meet, with first and last sighting, count and coordinates. Has its own tab in the Meteor GUI |
| **Farm Aura** | Plants a chosen crop on every tilled dirt in reach |
| **Fast Rename** | Renames items in bulk at an anvil |
| **Grinder** | Automates grindstone actions |
| **Incognito** | Covers your coordinates and minimap for screenshots and streams |=
| **Light Levels** | Improved from meteor's. Where mobs can spawn, as clean coloured squares, with a spawn area filter per dimension |
| **Portal Print Detector** | Finds the footprint a removed nether portal leaves behind by finding 4x1 holes or 4x1 netherrack in nylium or soul sand/soil |
| **Portal Scraps** | Finds leftover obsidian in the Nether. Ignores ruined portals using crying obsidian and chests in them, and optionally ignores highways and ring roads |
| **Rare Item Highlighter** | Highlights rare, unique and anomalous items in container slots |
| **Shulker Dyer** | Dyes shulker boxes and bundles in the crafting grid |
| **Rubberband Notifier** | Reports rubberbands with how many ticks you lost and how far you were moved |
| **Tablist Optimisations** | Makes the tab list cheap to draw on servers with huge player counts |
| **Team Trees** | Plants saplings, either replanting a fixed plot you give coordinates for or scattering them across the ground around you |
| **Tiller** | Tills nearby dirt and grass |
| **Via Texture Fix** | Repairs the names, item and block textures broken by ViaFabricPlus |
| **XCarry** | Store items in the inventory crafting grid |


## Extra tabs

Two tabs are added to the Meteor GUI.

**Encounters** lists every player you have met, with buttons to add or remove them as a Meteor
friend. It only records while the Encounters module is on.

**Icons** sets the category icon for every module category, including categories belonging to other
add-ons.

## Where your data is

Anything meant to stay after an update is stored outside Meteor's config, in `.minecraft/jjs-tools/`:

```text
.minecraft/jjs-tools/
├── accounts/
│   └── <your-uuid>/
│       ├── encounters.json    Players you have met
│       └── bed-marker.json    Your last bed respawn point
└── category-icons.json        Custom category icons
```

Encounters and your bed are kept per account, so two accounts on one install do not overwrite
each other. Category icons are shared.

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

## License

GPL-3.0. See [LICENSE](LICENSE).

Parts are adapted from other GPL-3.0 projects, with attribution in the source files that use them.

[meteor]: https://meteorclient.com
[fabric]: https://fabricmc.net
[xaeroplus]: https://github.com/rfresh2/XaeroPlus
[vfp]: https://github.com/ViaVersion/ViaFabricPlus
