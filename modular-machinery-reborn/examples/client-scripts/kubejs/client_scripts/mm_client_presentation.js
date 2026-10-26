// Client files: install on every client that should use this presentation.
MachineClientModels.register('client_demo:working', {
  model: 'client_demo:geo/controller.geo.json',
  texture: 'modular_machinery_reborn:textures/block/blockcasing_circuitry.png',
  animation: 'client_demo:animations/controller.animation.json',
  idle: 'idle',
  working: 'working'
})

MachineClientEvents.guiRender('client_demo:press', event => {
  const c = event.controller
  event.addInfo('客户端附加信息 / Client information')
  event.addInfo('进度 / Progress: ' + c.progress + '/' + c.duration)
})

MachineClientEvents.modelGet('client_demo:press', event => {
  if (event.controller.working) event.modelName = 'client_demo:working'
})

MachineClientEvents.modelAnimation('client_demo:press', event => {
  event.transitionLengthTicks = 5
  event.animationSpeed = 1
  if (event.controller.working) {
    event.setAnimation('working', true)
  } else {
    event.setAnimation('idle', true)
  }
})
