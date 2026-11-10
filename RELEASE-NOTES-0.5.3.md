# 0.5.3

For Minecraft 1.21.4, 1.21.5, 1.21.8 and 1.21.11 with Meteor Client. Download the jar whose
name matches your Minecraft version, for example `jjs-tools-0.5.3+1.21.4.jar`. Close the game
before replacing the jar.

## Changes

- **JJ's theme stays your choice.** JJ's theme is still the default the first time you start
  the game with JJ's Tools. After that, if you switch Meteor's GUI back to the Meteor theme, it
  stays on Meteor's theme across restarts instead of switching back to JJ's theme on every boot.
  The game remembers that the default was applied in `meteor-client/jjs-tools/default-theme-set`.

- **Module Wheel: animation time slider.** A new `animation-time` setting, in milliseconds,
  sets how long the wheel takes to open and close. The default is 90 ms, so it is quick.
  Set it to 0 to turn the animation off: the wheel appears and closes instantly. The Control
  Panel's `animation-speed` setting no longer affects the wheel.

- **Module Wheel: no delay before the animation.**
  - The wheel now opens on the frame you press the key, instead of waiting for the next game
    tick.
  - Letting go of the key is also checked every frame, so the choice happens straight away.
  - The slots no longer come in one after another. They all move out together, over a
    shorter arc.

- **Now runs on 1.21.4, 1.21.5 and 1.21.8 as well as 1.21.11.** One codebase builds a jar for
  each version. A few things only exist on newer versions and are left out on older ones:
  - Ctrl+F in Meteor's module search needs Meteor's 1.21.9 or newer builds.
  - Meteor's confirm buttons need its 1.21.6 or newer builds; older ones use plain buttons.
  - On 1.21.4, Light Levels always draws through blocks, because Meteor's 1.21.4 build has no
    depth-tested renderer.

## Building from source

```
.\gradlew.bat build "-Pminecraft_version=1.21.4"
.\gradlew.bat build "-Pminecraft_version=1.21.5"
.\gradlew.bat build "-Pminecraft_version=1.21.8"
.\gradlew.bat build "-Pminecraft_version=1.21.11"
```

## Also in this version

- Version bumped to 0.5.3.
