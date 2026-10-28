// Alternative to embers-datapack. Install only one equivalent definition.
MachineRegistryEvents.registry(event => {
  event.machine('compat_demo:embers_processor')
    .localizedName('余烬加工机')
    .requiresBlueprint(false).parallelizable(false)
    .part(-1, 0, 0, 'modular_machinery_reborn:item_input_hatch')
    .part(1, 0, 0, 'modular_machinery_reborn:item_output_hatch')
    .part(0, -1, 0, 'modular_machinery_reborn:ember_input_hatch')
    .register()
})
ServerEvents.recipes(event => {
  event.recipes.modular_machinery_reborn.machine('compat_demo:embers_processor')
    .registryName('embers_stone').time(100).parallelism(1)
    .itemInput('minecraft:cobblestone', 1)
    .emberInput(100.5)
    .itemOutput('minecraft:stone', 1)
    .id('compat_demo:embers_stone')
})
