# Modular Machinery: Community Edition Reborn

A custom multiblock machinery framework for **Minecraft 1.20.1 / Forge**, reimplementing Modular Machinery: Community Edition from Minecraft 1.12.2. Turn your own structures into processing machines, define their item, fluid, energy, gas, mana, Aura, LP and Demon Will inputs and outputs, then expand production with parallel processing, factory threads and upgrades.

Made for modpack authors, server administrators and players who want to design their own production systems. Define structures with JSON data packs, configuration files or KubeJS scripts, and processing recipes with data packs or KubeJS. The mod JAR includes no machine definitions or machine processing recipes; optional examples are available separately in the repository.

## Features

- **Machine design:** arbitrary shapes and layers, blockstate and structure NBT conditions, alternative blocks, variable sets, component selector tags and repeating dynamic sections. Use a generic controller or declare dedicated machine and factory controllers before startup. Scripts can attach block predicates to structure parts.
- **Recipe authoring:** items, fluids, energy per tick, Mekanism gases, Botania mana, Nature’s Aura, Blood Magic 3 LP and Demon Will, PneumaticCraft compressed air and Embers Rekindled Ember, alternative ingredient groups, numeric interfaces, probabilistic consumption and outputs, output ranges and optional catalysts that modify one craft.
- **Production scaling:** parallel controllers, built-in parallelism, independent factory recipe threads, core threads, upgrade buses, structure modifiers and component groups.
- **Advanced scripting:** callbacks scoped to machines, recipes and upgrades; item predicates and transformations; durability, fuel and delayed requirements; persistent custom data, named runtime modifiers and factory thread control.
- **Building and inspection:** blueprint and JEI structure previews, layer views, world ghost projections, a collapsible preview toolbar and visible-block status, F3 draw statistics, automatic assembly and a selection tool that exports existing structures as JSON fragments. JEI supports gas sprites and a green Aura gauge.
- **Pack integration:** KubeJS machine and recipe APIs and lifecycle events; furnace recipe imports and recipe reuse between machines; optional AE2 unified ME buses, the **ME Machinery Pattern Provider** and **ME Machinery Pattern Mirror Image** for items, fluids and gases through Applied Mekanistics; providers include 36 pattern slots, persistent item/fluid/gas buffers, five modes, per-pattern isolation, powered returns, shared manual inputs and memory-card-linked mirrors; optional Flux Networks / Draconic Evolution long energy, exact Jade hatch energy views, and GeckoLib emissive, translucent and bloom model effects.

Machine definitions load on the server and synchronize to clients. Most data changes support `/reload`; registering new blocks, including dedicated controllers, requires a restart. Install KubeJS, JEI, Mekanism, AE2, Jade, GeckoLib, Botania, Nature’s Aura, Blood Magic, PneumaticCraft and Embers Rekindled as needed, together with their own dependencies.

## Current status

Current version: **0.121.0**, October 28, 2026; protocol **12**. Creative authors with permission level 2 can open `/mm-recipe_editor` for resource forms, JEI ingredient drops, typed NBT, advanced source editing, server validation and JSON/KubeJS export. The enhanced structure tool remains included. Offline checks and production build passed; real gameplay acceptance is pending. See the [editor guide](Visual-recipe-editor.md) and [update record](更新记录-0.121.0.md).

This is a reimplementation, so existing scripts and pack content need migration. The old CraftTweaker / ZenScript API does not run directly, and the original third-party recipe adapters are not all available. GT / IC2 energy integration remains unavailable. Preview NBT now drives block entity rendering in the isolated preview scene; third-party visual behavior still needs in-game verification. Client GUI information, model selection and animation hooks are now available; their visual behavior still needs in-game verification. ME gas features require AE2, Mekanism and Applied Mekanistics 1.4.3; clients and servers must update together to protocol **11**. See [Known limitations](已知限制.md) (Chinese) for the current scope.

Configuration and fallback rules are documented in [World model hiding](世界模型隐藏.md) (Chinese). Hiding changes visuals only; collisions, ports and structure checks continue to work.

See [Factory energy information](工厂能量信息.md) (Chinese) for display and rate semantics.

Creator tooling roadmap: [structure-tool enhancements and a visual recipe editor](创作者工具计划.md). The structure-tool first version is implemented; the visual recipe editor remains planned.

## Getting started

Follow the [Usage guide](使用说明.md) (Chinese) to build a minimal working machine, then use the [Recipe guide](KJS-配方指南.md) (Chinese) to add processing recipes. The [Advanced scripting guide](高级脚本指南.md) (Chinese) covers runtime control and scoped callbacks. Examples use Reborn's current file layout, fields and scripting interfaces.

[中文简介](简介.md) · [Documentation index](README.md) · [Changelog](../迁移日志.md)

For file placement and reload behavior, see [Installation and reload](安装与重载.md). The [advanced structure example](进阶结构示例.md) and [ME automation guide](ME自动化指南.md) provide complete follow-up exercises (Chinese).
