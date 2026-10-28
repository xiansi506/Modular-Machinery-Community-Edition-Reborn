# 客户端 GUI／模型／动画脚本示例（0.113.0+）

需要 Minecraft 1.20.1 / Forge、Reborn、KubeJS；模型和动画还需要 GeckoLib。仅追加 GUI 信息行时不需要 GeckoLib。

1. 将 `datapack/` 复制到世界的 `datapacks/reborn-client-demo/`。
2. 将 `resourcepack/` 复制到客户端 `resourcepacks/reborn-client-demo/`，在资源包菜单启用。
3. 将 `kubejs/client_scripts/mm_client_presentation.js` 复制到每个客户端实例的 `kubejs/client_scripts/`。
4. 数据包修改用 `/reload`；客户端脚本修改用 `/kubejs reload client_scripts`，资源修改后刷新资源包。
5. 在通用控制器蓝图中选择 `client_demo:press`，左右分别放输入仓和输出仓。放入铁锭后开始加工。

控制器界面增加客户端信息行。控制器上方显示一个方块模型：空闲用普通外壳贴图，运行时改为电路外壳贴图并转动。示例复用模组自带贴图，不需要额外图片。0.114.0 起，模型成功显示后会隐藏底部原控制器模型；输入／输出仓默认保留。将机器 JSON 中的 `hide-components-when-formed` 改为 `true`，可以同时隐藏两个仓的外观，端口和交互继续工作。详见 [世界模型隐藏](../../docs/进阶/世界模型隐藏.md)。

GUI／模型事件可省略机器 ID 来订阅所有机器，也可像示例一样指定 ID。`event.controller` 只读；`customData` 返回客户端已同步数据的副本。事件可能每帧多次调用，回调宜只计算显示结果。计算、扣料、修改器等服务端工作仍放在服务器脚本。

具名模型在客户端脚本重载时清空并重新注册。资源刷新会重建动画缓存而保留当前模型注册。找不到具名模型或渲染资源出错时回退机器定义的模型；定义也无可用模型时保留普通控制器显示，日志会说明错误。

本示例已做语法、资源引用和生产逻辑检查，实际 GUI、贴图及动画效果待游戏验收。见 [客户端脚本指南](../../docs/进阶/客户端脚本指南.md)。
