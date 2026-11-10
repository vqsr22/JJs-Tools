# 0.4-beta

Everything since `0.3-beta`. For Minecraft 1.21.11 with Meteor Client.

Close the game before replacing the jar. Swapping it while Minecraft is running leaves the open
file handle pointing at something that no longer matches, and the next class the game needs comes
back as garbage.

## New modules

- **Sign Items** — write an item on a sign and that item is drawn on it, like an item frame.
  `elytra` shows an elytra, `elytra:enchanted` shows one with the glint, `:x16` sets the count.
  Every line is tried, so a sign can keep a heading and still show something. Client side only,
  and the sign is untouched, so it works on signs you did not place. The click can be handed on
  to whatever the sign is covering: a sign is a block rather than an entity, so the ray cannot
  simply be fired again ignoring entities, and the line of sight is walked forward a step at a
  time instead, skipping signs. Only blocks that hold something are opened, so a sign on a plain
  wall will not get a block placed against it.

- **Better Hitreg** — makes hits feel instant. The flinch, the sound and the crit and sharpness
  particles play on the click rather than a round trip later, and the server's own copy is
  swallowed when it arrives, narrowly: only a hit whose attacker is you, and only one predicted
  in the last second. The flinch is restarted from the server's copy rather than skipped, so the
  server stays the authority on how long the target stays red and a wrong prediction still ends
  up looking right. Nothing about the hit itself changes, and this does not improve latency.
  Ported from Better Hitreg by jasspir (Apache-2.0).

## Added to existing modules

- **Advanced Autolog** — standing still now means nothing is happening, not just that you are not
  moving. Turning the camera or holding any key counts as being at the keyboard and starts the
  clock again. The wait is 3 to 60 seconds.

- **Advanced Autolog** — mobs knocking you about no longer count as you moving, so the one zombie
  that found you cannot keep you logged in all night. There is a cap on how far that can excuse,
  since knockback runs out in a few blocks and anything flying you passes the cap in under a
  second.

- **Anti Item Frame** — a frame hung on a chest can hand the click to the chest. Once the ray has
  found the frame the game stops looking, so cancelling alone leaves you with nothing happening;
  the ray is fired again with entities left out instead. Only blocks that hold something, for the
  same reason as above.

## Notes

Sign Items draws through the sign renderer's own text transform, so it should land correctly on
standing, wall and hanging signs alike. If an item sits wrong, the Placement group has
`item-size`, `height` and `lift`.
