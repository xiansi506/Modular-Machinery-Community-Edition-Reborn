// Alternative to the quickstart data pack. Install only one of the two.
MachineRegistryEvents.registry(event => {
  event.machine('tutorial:stone_processor')
    .localizedName('圆石加工机')
    .requiresBlueprint(false)
    .parallelizable(true)
    .maxParallelism(64)
    .internalParallelism(1)
    .part(-1, 0, 0, 'modular_machinery_reborn:item_input_hatch')
    .part(1, 0, 0, 'modular_machinery_reborn:item_output_hatch')
    .part(0, -1, 0, 'modular_machinery_reborn:energy_input_hatch')
    .register()
})

ServerEvents.recipes(event => {
  event.recipes.modular_machinery_reborn.machine('tutorial:stone_processor')
    .registryName('stone')
    .time(100)
    .parallelism(64)
    .itemInput('minecraft:cobblestone', 1)
    .energyInput(20)
    .itemOutput('minecraft:stone', 1)
    .id('tutorial:stone')
})
