# 0.5.3

For Minecraft 1.21.11 with Meteor Client. Close the game before replacing the jar.

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

## Also in this version

- Version bumped to 0.5.3.
