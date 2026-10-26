# Modular Machinery: Community Edition Reborn

A custom multiblock machinery framework for **Minecraft 1.20.1 / Forge**, reimplementing Modular Machinery: Community Edition from Minecraft 1.12.2. Turn your own structures into processing machines, define their item, fluid, energy, gas, mana, Aura, LP and Demon Will inputs and outputs, then expand production with parallel processing, factory threads and upgrades.

Made for modpack authors, server administrators and players who want to design their own production systems. Define structures with JSON data packs, configuration files or KubeJS scripts, and processing recipes with data packs or KubeJS. The mod JAR includes no machine definitions or machine processing recipes; optional examples are available separately in the repository.

## Features

- **Machine design:** arbitrary shapes and layers, blockstate and structure NBT conditions, alternative blocks, variable sets, component selector tags and repeating dynamic sections. Use a generic controller or declare dedicated machine and factory controllers before startup. Scripts can attach block predicates to structure parts.
- **Recipe authoring:** items, fluids, energy per tick, Mekanism gases, Botania mana, Nature’s Aura, Blood Magic 3 LP and Demon Will, alternative ingredient groups, numeric interfaces, probabilistic consumption and outputs, output ranges and optional catalysts that modify one craft.
- **Production scaling:** parallel controllers, built-in parallelism, independent factory recipe threads, core threads, upgrade buses, structure modifiers and component groups.
- **Advanced scripting:** callbacks scoped to machines, recipes and upgrades; item predicates and transformations; durability, fuel and delayed requirements; persistent custom data, named runtime modifiers and factory thread control.
- **Building and inspection:** blueprint and JEI structure previews, layer views, world ghost projections, a collapsible preview toolbar and visible-block status, F3 draw statistics, automatic assembly and a selection tool that exports existing structures as JSON fragments.
- **Pack integration:** KubeJS machine and recipe APIs and lifecycle events; furnace recipe imports and recipe reuse between machines; optional AE2 unified ME buses, the **ME Machinery Pattern Provider** and **ME Machinery Pattern Mirror Image** for items, fluids and gases through Applied Mekanistics; providers include 36 pattern slots, persistent item/fluid/gas buffers, five modes, per-pattern isolation, powered returns, shared manual inputs and memory-card-linked mirrors; optional Flux Networks / Draconic Evolution long energy, exact Jade hatch energy views, and GeckoLib emissive, translucent and bloom model effects.

Machine definitions load on the server and synchronize to clients. Most data changes support `/reload`; registering new blocks, including dedicated controllers, requires a restart. Install KubeJS, JEI, Mekanism, AE2, Jade, GeckoLib, Botania, Nature’s Aura and Blood Magic as needed, together with their own dependencies.

## Current status

This introduction covers **0.118.0, October 27, 2026**. Botania mana, Nature’s Aura, and Blood Magic 3 LP and five Demon Will types are implemented through eight optional hatches, transactional recipes, KubeJS methods, menus and JEI descriptions. Offline checks and the production build passed; **in-game acceptance, deployment and upload are pending**. Other magic integrations remain deferred. See [Magic resources](Magic-resources.md).

This is a reimplementation, so existing scripts and pack content need migration. The old CraftTweaker / ZenScript API does not run directly, and the original third-party recipe adapters are not all available. GT / IC2 energy integration remains unavailable. Preview NBT now drives block entity rendering in the isolated preview scene; third-party visual behavior still needs in-game verification. Client GUI information, model selection and animation hooks are now available; their visual behavior still needs in-game verification. ME gas features require AE2, Mekanism and Applied Mekanistics 1.4.3; clients and servers must update together to protocol **7**. See [Known limitations](已知限制.md) (Chinese) for the current scope.

Configuration and fallback rules are documented in [World model hiding](世界模型隐藏.md) (Chinese). Hiding changes visuals only; collisions, ports and structure checks continue to work.

See [Factory energy information](工厂能量信息.md) (Chinese) for display and rate semantics.

## Getting started

Follow the [Usage guide](使用说明.md) (Chinese) to build a minimal working machine, then use the [Recipe guide](KJS-配方指南.md) (Chinese) to add processing recipes. The [Advanced scripting guide](高级脚本指南.md) (Chinese) covers runtime control and scoped callbacks. Examples use Reborn's current file layout, fields and scripting interfaces.

[中文简介](简介.md) · [Documentation index](README.md) · [Changelog](../迁移日志.md)
