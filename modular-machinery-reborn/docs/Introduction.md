# Modular Machinery: Community Edition Reborn

A custom multiblock machinery framework for **Minecraft 1.20.1 / Forge**, reimplementing Modular Machinery: Community Edition from Minecraft 1.12.2. Turn your own structures into processing machines, define their item, fluid, energy and gas inputs and outputs, then expand production with parallel processing, factory threads and upgrades.

Made for modpack authors, server administrators and players who want to design their own production systems. Define structures with JSON data packs, configuration files or KubeJS scripts, and processing recipes with data packs or KubeJS. The mod JAR includes no machine definitions or machine processing recipes; optional examples are available separately in the repository.

## Features

- **Machine design:** arbitrary shapes and layers, blockstate conditions, alternative blocks, variable sets, component selector tags and repeating dynamic sections. Use a generic controller or declare dedicated machine and factory controllers before startup. Item recipes support NBT conditions; structure NBT matching remains unavailable.
- **Recipe authoring:** items, fluids, energy per tick, Mekanism gases, alternative ingredient groups, numeric interfaces, probabilistic consumption and outputs, output ranges and optional catalysts that modify one craft.
- **Production scaling:** parallel controllers, built-in parallelism, independent factory recipe threads, core threads, upgrade buses, structure modifiers and component groups.
- **Building and inspection:** blueprint and JEI structure previews, layer views, world ghost projections, automatic assembly and a selection tool that exports existing structures as JSON fragments.
- **Pack integration:** KubeJS machine and recipe APIs and lifecycle events; furnace recipe imports and recipe reuse between machines; optional AE2 ME buses, fluid buses and processing pattern providers; optional Jade information and GeckoLib controller models and animations.

Machine definitions load on the server and synchronize to clients. Most data changes support `/reload`; registering new blocks, including dedicated controllers, requires a restart. Install KubeJS, JEI, Mekanism, AE2, Jade and GeckoLib as needed, together with their own dependencies.

## Current status

This introduction covers **0.107.3, October 25, 2026**. Development is ongoing. The features above are implemented, but their verification coverage varies; the list does not mean every feature has been tested in game. The latest repair rounds passed the 2,669-check offline suite and the 29-, 22- and 18-check focused suites, plus build and release checks. Version 0.107.3 has not yet undergone in-game acceptance testing. Real AE2 networks, multiplayer LAN sessions, gas pipes, automatic assembly and visual behavior still need verification.

This is a reimplementation, so existing scripts and pack content need migration. The old CraftTweaker / ZenScript API does not run directly, and the original third-party recipe adapters are not all available. GT / IC2 energy integration and MekEng gas patterns remain unavailable. See [Known limitations](已知限制.md) (Chinese) for the current scope.

## Getting started

Follow the [Usage guide](使用说明.md) (Chinese) to build a minimal working machine, then use the [Recipe guide](KJS-配方指南.md) (Chinese) to add processing recipes. Examples use Reborn's current file layout, fields and scripting interfaces.

[中文简介](简介.md) · [Documentation index](README.md) · [Changelog](../迁移日志.md)
