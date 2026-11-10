# 0.3-beta

Everything since `0.2-beta`. For Minecraft 1.21.11 with Meteor Client.

Close the game before replacing the jar. Swapping it while Minecraft is running leaves the open
file handle pointing at something that no longer matches, and the next class the game needs comes
back as garbage.

## New modules

- **Kit Creator** — scan a shulker box, then lay another one out exactly the same. Scan and Create
  sit in the shulker's own title row. Items already inside are moved into their scanned slots, the
  rest come out of your inventory, and anything the scan does not account for is shifted back out.
  Matches by item and enchantments and ignores names, so a fresh Mending pick fills the slot a
  renamed, worn one came out of. Anything you are short of leaves its slot empty.
- **Chat Presets** — four presets, each with a key, a list of lines and a delay. More than one line
  is sent in order with the delay between, for anything a server answers one step at a time. A line
  starting with `/` goes as a command.
- **No Jolt** — holds the health and hunger icons still instead of letting vanilla shake them at
  you. Separate toggles for the hunger shake when your saturation runs out and the heart shake when
  your health and absorption together are low.

## New tab

- **Conflicts** — modules from different add-ons that wanted the same name, and a way to rename
  either one.

  Meteor files modules by name, and a second module taking a name that is already spoken for does
  not sit beside the first: it replaces it. That is why a clash looks like one add-on's module
  having vanished rather than like a conflict, and why nothing after the fact could recover it.

  Clashes are now settled as modules register, before Meteor files them, so both sides survive and
  both are saved under their own name from the first launch. The automatic name is the original
  plus the add-on it came from. Rename either side yourself in the tab if you would rather.

## Added to existing modules

**Advanced Autolog**
- Health threshold, default four hearts.
- Totem pops, with a chat line on each pop saying how many are left.
- End crystals placed close enough to hurt you, read as a threat when one appears rather than when
  it goes off.
- A player coming within range, with options to ignore friends and ignore anyone wearing no armour.
- Reaching a set of coordinates in a chosen dimension.
- Standing still, from ten seconds to ten minutes. Instead of leaving it can say a line or two in
  chat with a delay between, so a stalled Baritone gets told to carry on.
- `disable-auto-reconnect`, which was always happening with no way to turn it off.

**Fast Rename**
- Opens the anvil that drops into place when the one you are using breaks. The break is read from
  the block rather than from the screen, because nothing closes the screen when the anvil under it
  is destroyed, and closing it yourself leaves the anvil standing.

**Light Levels**
- A per-mob filter. A square only shows if a zombie, skeleton, creeper, spider or enderman would
  actually fit there. A spider is wide enough to need its neighbours clear, an enderman needs three
  blocks of headroom, and a creeper is short enough to fit under a ceiling that rules out a
  skeleton.

**Chat Tweaks**
- Hides a trailing run of random characters with no bracket or bar in front of it, which is what
  most clients now tag on.
- `hide-junk-messages` hides the whole line rather than trimming it.

## Fixed

- **Category icons did not apply until you changed them.** Applying once at startup was never
  enough: add-ons initialise in turn, so any that load afterwards never saw their stored icon, and
  the icon is read as the GUI builds its widgets. They are now reapplied as a screen opens.
- **Meteor's GUI scale** can go down to 0.5 instead of stopping at 0.75.

## Removed

- **Auto Smith.**
- Advanced Autolog's ETA settings, replaced by the simpler coordinate trigger.
