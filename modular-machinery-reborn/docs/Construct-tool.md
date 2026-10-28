# Construct tool

For **Reborn 0.119.1 / Minecraft 1.20.1 / Forge**, protocol **10**. Update both client and server.

## Select and export

1. Use creative mode with command permission level 2. Right-click individual blocks to select them; click again to deselect. White outlines appear while holding the tool, within 32 blocks.
2. Right-click the machine controller to export. The tool now runs before block GUI interaction, so sneaking is unnecessary for selecting hatches or exporting. Unauthorized players retain normal block interaction.
3. Find the JSON fragment in the server instance's `config/modular_machinery_reborn/exports/` directory. In singleplayer this is your local instance; on a dedicated server ask its administrator for the file.
4. Names use a lowercase player name and timestamp; collisions add `_1`, `_2`, etc. Existing exports are never replaced.
5. The selection clears only after a complete successful write. Write errors, unreadable positions and NBT that cannot round trip preserve the selection. Load missing chunks and retry; the tool does not force chunk loading.
6. Changing dimension, respawning or disconnecting clears the selection. Players have separate selections.

Selection remains per block; box selection, undo and a quick-clear gesture are not provided. The outline range does not limit the selection size or keep remote chunks loaded.

## Use the fragment

The file contains only `parts` and is not automatically loaded. Copy its parts into a full machine definition with `registryname` and other required fields, then save that definition in `data/<namespace>/machinery/` in a data pack or in `config/modular_machinery_reborn/machinery/`. Use `/reload`; new dedicated controller registration requires a restart. Configuration file names must follow lowercase resource-path rules.

Positions and native block states are normalized together to a north-facing controller, including stairs, log axes and rail shapes. Do not rotate them again manually. Every block-state property is captured; remove constraints that should remain flexible.

Old fragments in `machinery/` are preserved and are not migrated automatically. Move or complete those files yourself if the loader reports missing machine metadata.

## NBT snapshots

Generated `nbt` uses a versioned literal envelope, for example `["mmr:literal_nbt_v1", "{Items:[]}"]`. It retains numeric types, typed arrays, empty containers and literal strings. Snapshot fields are required while additional compound fields are allowed; list order and empty lists are matched literally. Coordinates and transient casing tint are excluded.

Inventory, energy and progress fields can still become structure requirements. Remove unnecessary `nbt` conditions or replace them with a focused hand-written template. Existing hand-written regex/range templates keep their previous behavior. `preview-nbt` remains available for separate display data.

The targeted offline suite uses real production export/parsing/matching methods and native chest/furnace data, with lightweight world and player fixtures. In-game clicks, outlines, portals, multiplayer and real OpenGL behavior still require acceptance. See the [fix record](修复记录-0.119.1.md) for build and validation details.
