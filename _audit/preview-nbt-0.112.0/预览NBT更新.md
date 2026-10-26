# 0.112.0 预览 NBT 更新

日期：2026-10-26。基于工作区 0.111.0，保留此前气体、供应器、界面及高级脚本改动。Git 基线为 `main / 36f34b0`；本轮未提交、推送或部署。

## 已实现

- 蓝图与 JEI 共用的 `StructurePreview` 携带每个位置的具体显示 NBT，构造器和读取器复制数据。
- `MachineSchema` 加载时校验 `preview-nbt`；JSON 数据包、配置机器和 KubeJS `.previewNBT()` 使用相同路径。现有定义 JSON 同步已包含字段，通信协议保持 **5**。
- `PreviewLevel` 是独立、只读、不注册逻辑 tick 的场景，仅包含当前显示的方块和实体；查询邻居及区块视图不访问实际世界。区块视图缓存最多 32 项。
- `PreviewBlockEntities` 按当前候选状态创建实体，先登记全部实体再读入显示 NBT，支持相邻方块查询。类型和坐标由候选状态与预览位置决定，数据中的 `id/x/y/z` 不会替换它们。
- 方块模型使用实体 ModelData；特殊方块调用 `BlockEntityRenderDispatcher.renderItem`，避开实际世界的距离裁剪；有实体渲染器的箱子等不重复绘制默认物品模型。
- 按有效候选状态、分层和世界上下文缓存实体，最多保留 8 个场景；断线和资源重载清理。新定义生成新的预览，不复用旧数据。
- 实体创建／NBT 读取／渲染失败记录日志并回退模型。异常渲染器在当前场景中停用；独立 PoseStack 避免未平衡的第三方渲染操作破坏下一个方块。

## 1.12.2 源码对照

本机 `_mmce-src/ModularMachinery-Community-Edition-master/src/main/java/`：

- `github/kasuminova/mmce/client/gui/widget/impl/preview/WorldSceneRendererWidget.java:120`：创建 dummy world 内的样本实体，向 BlockInfo 传递 `getPreviewTag()`。
- `com/cleanroommc/client/util/BlockInfo.java:103`：安装样本实体后调用 `readFromNBT(teTag)`。
- `hellfirepvp/modularmachinery/client/util/BlockArrayRenderHelper.java:241`：为样本状态创建实体、缓存 TESR；建模读取 `getPreviewTag()`。
- `hellfirepvp/modularmachinery/common/util/BlockArray.java:547`：显示标签与匹配标签分别存储。因此无 `preview-nbt` 时不把匹配谓词自动加载成显示数据。

1.20.1 用 `EntityBlock.newBlockEntity`、`BlockEntity.load`、Forge ModelData 和 BlockEntityRenderDispatcher 接入当前相机／GUI 渲染路径。

旧解析器会将非数值字符串构造成正则对象，合法的 JSON 文本组件可能因此被拒绝。本版将具体显示 NBT 单独解析：字符串按字面保留，普通 JSON 数字／布尔可写，数值类型后缀与原写法兼容；允许空字符串、空列表和空对象。同一列表仍须同类型。原匹配 NBT 的正则、比较及字符串标量要求保持原样。

## 验证

| 项目 | 结果 | 本机证据 |
|---|---|---|
| 预览 NBT 专项 | **27 / 0** | PreviewNbtCheck.java、preview-final.log |
| 完整旧套件 | **2669 / 0** | harness-final.log、harness-run-final.txt |
| 高级脚本 | **57 / 0** | advanced-final.log |
| 界面／供应器／气体 | **28 / 0、34 / 0、28 / 0** | ui/provider/gas-final.log |
| 前三批缺陷回归 | **18 / 0、22 / 0、29 / 0** | four/state/fix-final.log |
| 可选依赖缺席反射 | **44 / 0** | isolation-final.log |
| 最终构建 | **build/reobf 成功，30s** | build-final.log |
| 归档验证 | **177 JSON、中英各 401 键、SRG、版本、协议 5 通过** | artifact-validation.txt |
| 文档与示例 | 23 JSON、13 段 JS 加完整预览示例脚本；34＋6 本地链接、quickstart 一致性，以及预览数据包／KubeJS 展开后的相同结构与 NBT | check-docs.cjs、docs-validation.txt |

专项直接使用生产解析器和场景，创建实际 ChestBlockEntity、BannerBlockEntity、SignBlockEntity，检查箱子名称／物品、旗帜图案、告示牌文字／颜色／发光数据；验证坐标保护、NBT 副本、层切换、候选实体更换、旋转／动态段、场景隔离与缓存。

渲染分派专项使用真实 BlockEntityRenderDispatcher，替换为只检查参数的测试渲染器，验证 NBT／场景／矩阵传递，以及注入异常后不重复调用、不损坏外部 PoseStack。**没有启动 Minecraft/OpenGL，没有验收原版或第三方渲染器的实际画面。**

## 产物与使用

- 版本：**0.112.0**；协议仍 **5**，两端安装包保持一致。
- 来源：`modular-machinery-reborn/build/reobfJar/output.jar`。
- 归档：`releases/modular-machinery-reborn/v0.112.0/modular-machinery-reborn-0.112.0.jar`。
- 大小：**1,268,932 字节**。
- SHA-256：`5D23FAB3279C2539C0301CF33E931129361B78FD9A714E6681A3245AD530F197`。
- [JSON／KubeJS 示例](../../modular-machinery-reborn/examples/preview-nbt/README.md) · [高级脚本指南](../../modular-machinery-reborn/docs/高级脚本指南.md)。

蓝图／JEI 显示字段按目标模组的 1.20.1 实体格式填写。预览不执行加工、开容器或赠送物品；需要 `lit`、朝向等状态时，写在方块状态描述符中。世界投影仪继续使用方块模型，这与本轮 GUI 显示 NBT 分开。

**未实机、未部署、未提交／推送或创建 Release**，实例仍 0.107.1。真实字体、资源包、GPU 画面及第三方实体渲染器效果仍需游戏验收。客户端 GUI／动画脚本、世界内成型组件隐藏、工厂能量信息聚合及既有暂缓后端留后续。

本轮相对 0.111.0 的源码清单见 changed-source-files.txt；before 快照、classes、参数和原始日志仅作本机证据，不批量上传。
