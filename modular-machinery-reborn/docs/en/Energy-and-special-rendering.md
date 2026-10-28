# Long energy, gas sprites and model materials (0.118.6)

0.118.4 preserves JSON long energy integers through settlement, removes the premature quarter-long cap, and saturates only the final draw at `Long.MAX_VALUE`. Modifier coefficients retain their existing float aggregation semantics. JavaScript Number precision and ordinary Forge Energy int transfer limits still apply; see the [bilingual patch notes](../归档/版本记录/修复记录-0.118.4.md).

These features have passed offline checks and a production build. In-game acceptance is pending. Update clients and servers together to current protocol **12**. Back up the world before upgrading; energy values above the int range cannot be restored faithfully by older versions.

## Energy hatches

Edit `config/modular_machinery_reborn-common.toml`. For example:

```toml
["energyhatch.size"]
TINY = 10000000000
["energyhatch.limit"]
TINY = 5000000000
[general]
enable-fluxnetworks-integration = true
```

Capacity supports `1..9223372036854775807`; transfer supports `1..9223372036854775806`. Defaults retain the previous tier values. Restart the world, then reload the chunk or replace an existing hatch to rebuild it with the new settings. Menus, comparators, recipe transactions, saves and Jade hatch energy bars use the full long values. Aggregated totals saturate at the long maximum.

Legacy int saves load by replacement, independently of transfer limits. External input hatch ports accept energy; external output hatch ports supply it. Recipes retain access to their internal buffers.

## Optional energy connections

Install the corresponding **1.20.1** mods and their dependencies as needed. Flux Points supply input hatches; Flux Plugs drain output hatches through Flux Networks' long capability. Its configuration switch defaults to true and requires a world restart. Draconic Evolution devices can use Brandon's Core's OP capability, with `1 FE = 1 OP`; configure their input/output modes and links as required by those mods.

Output hatches select one long capability per neighbor, with FE as a fallback. All six automatic output faces share one transfer budget per tick. Ordinary FE remains limited to `2147483647` per API call. Transfer settings bound each operation; external repeated pulls do not share a global tick budget.

API compatibility was checked against the official Flux Networks 1.20 branch and the Brandon's Core `1.20.1-3.2.1.302` API used by Draconic Evolution's 1.20 branch. Real networks, devices, multiplayer and world reloads still need acceptance testing. Other energy backends were not added.

## Gas visuals and model materials

JEI gas cells now draw the gas's animated atlas sprite, tint and tank shell. Existing descriptions, quantities, chances and script tooltips remain. Missing sprites fall back to text. Recipes need no changes. This does not add a new gas ingredient index or gas R/U lookup.

GeckoLib bone names recognize case-sensitive `emissive`, `bloom`, `transparent`, `emissive_transparent` and `bloom_transparent` prefixes. Children inherit their parents' flags. Both emissive prefixes use full brightness and a world-space mask feeding a two-pass blur and scene composition. Transparent bones blend using texture alpha without writing depth; the prefix does not make an opaque texture translucent.

Previews retain material effects without a full-screen halo. World bloom captures up to 64 emissive textures per frame; additional textures retain full brightness. Shader failures disable bloom until a resource reload and are logged. Reloading resources and disconnecting discard pending mask geometry. Embeddium, Oculus, shader packs, Fabulous mode and live resizing remain unverified in-game. Async VBO rendering and other external model loaders are outside this change.

[中文说明](../联动/能源与特殊渲染.md) · [Introduction](Introduction.md)
