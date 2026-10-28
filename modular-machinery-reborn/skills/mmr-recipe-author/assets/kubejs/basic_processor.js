// Alternative to assets/datapack. Install only one version of these definitions.
// Place in kubejs/server_scripts/. Supply FE to the energy input hatch.
MachineRegistryEvents.registry(event => {
  event.machine('skill_demo:basic_processor')
    .localizedName('基础加工机')
    .requiresBlueprint(false)
    .parallelizable(false)
    .part(-1, 0, 0, 'modular_machinery_reborn:item_input_hatch')
    .part(1, 0, 0, 'modular_machinery_reborn:item_output_hatch')
    .part(0, -1, 0, 'modular_machinery_reborn:energy_input_hatch')
    .register()
})

ServerEvents.recipes(event => {
  event.recipes.modular_machinery_reborn.machine('skill_demo:basic_processor')
    .registryName('stone')
    .time(100)
    .parallelism(1)
    .itemInput('minecraft:cobblestone', 1)
    .energyInput(20)
    .itemOutput('minecraft:stone', 1)
    .id('skill_demo:stone')
})
