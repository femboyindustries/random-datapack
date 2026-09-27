# ![ultra datapack shuffle!!!](./docs/uds-long.png)

> **DO NOT** run this mod on worlds that matter to you in any capacity. It will
> probably mess with your player state, mess with your world, world generation,
> and even corrupt the world.

**Still downloading datapacks manually???** In fact, are you ***still SELECTING
YOUR DATAPACKS MANUALLY???*** If so, a luddite like you would never even
*understand* the need for a mod such as this.

**Ultra Datapack Shuffle!!** picks a random datapack from Modrinth's API,
downloads it, and enables it without asking you for permission. The consequences
of this should be immediately obvious; datapacks range from tiny recipe and loot
tables tweaks to complete overhauls of the game, so any given datapack that's
downloaded can do any of the above to your game. Sometimes it'll be a datapack
that randomizes item drops, sometimes a datapack that adds new unrelated
mechanics, sometimes it'll be a datapack that doesn't work and doesn't do
anything, and sometimes it'll be a datapack that doesn't work and makes you
unable to move, jump or break blocks.

It's great fun, trust me.

## How

All of the functionality of this mod is stored away in the `/random-datapack`
command:

- `/random-datapack download` just downloads and loads a single datapack.
- `/random-datapack timer pause <false | true>` toggles the automatic 
  downloading (off by default).
- `/random-datapack timer set <interval>` can be used to set the interval, in
  ticks (1 minute by default).
- `/random-datapack filter <category>` filters the pool of datapacks to draw 
  from.
  - Use `/random-datapack filter` to clear the filter.
- `/random-datapack disable-all` disables every datapack, incase something goes
  very wrong.

You are expected to also be familiar with the functionality of the vanilla
`/datapack` command.