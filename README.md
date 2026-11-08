# Spawn Intensity (NeoForge 1.21.1)

Spawn multipliers (x1-x100) for mobs, loot, block drops and chests, faster growth, item despawn timer, and Apocalypse mode.

## Build the jar on GitHub (no PC needed)
1. In your repo, put this folder as `SpawnIntensityNeoForge/` and the workflow as `.github/workflows/build-neoforge.yml`.
2. Actions tab -> "Build NeoForge mod" -> download the **spawnintensity-neoforge-jar** artifact.
3. Put the jar in `mods` (NeoForge 1.21.1, Java 21).

If the build says it cannot find the NeoForge or plugin version, edit `neo_version` in `gradle.properties` / the plugin version in `build.gradle` to a real one from projects.neoforged.net.

## Config
`config/spawnintensity-common.toml` (live reload) or `/spawnintensity ...` commands, same as the Forge version. Right-click the **Spawn Sit** clock for status.

## Modded enemies
- Hostile = vanilla monsters, anything implementing `Enemy`, or the MONSTER category.
- `multiplyUntracked = true` also multiplies mods that spawn mobs without firing the normal spawn event.
- `spawnTypes` picks which spawn kinds multiply (default NATURAL, CHUNK_GENERATION, STRUCTURE).
- Still missed? Add it to `extraHostile`, e.g. `"somemod:thing"` or `"somemod:*"`. Use `spawnBlacklist` to protect mobs (bosses) from duplication.
- Big mobs try 10 spots around the original so they are not dropped for lack of room; the cap only counts mobs of the filtered kind.
