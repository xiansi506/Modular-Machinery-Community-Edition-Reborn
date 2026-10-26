// Alternative to the datapack: install only one definition of preview_nbt:showcase.
MachineRegistryEvents.registry(event => {
  const machine = event.machine('preview_nbt:showcase')
    .localizedName('预览 NBT 展示 / Preview NBT Showcase')
  for (const x of [-1, 0, 1]) {
    for (const z of [-1, 0]) machine.part(x, -1, z, 'minecraft:stone')
  }
  machine.part(1, 0, 0, 'minecraft:chest[facing=north]')
    .previewNBT({
      CustomName: '{"text":"Preview chest"}',
      Items: [{ Slot: '0b', id: 'minecraft:diamond', Count: '3b' }]
    })
    .part(0, 0, -1, 'minecraft:white_banner[rotation=0]')
    .previewNBT({ Patterns: [{ Pattern: 'bs', Color: 14 }] })
    .part(-1, 0, 0, 'minecraft:oak_sign[rotation=0]')
    .previewNBT({
      front_text: {
        messages: ['{"text":"NBT Preview"}', '"Line two"', '""', '""'],
        color: 'red',
        has_glowing_text: true
      }
    })
    .register()
})
