# 0.118.2 本地修复归档

修复 JEI 多方块预览七项问题：相机法线、成型控制器关联、动态材料同步、流体绘制、侧边拾取、选中图标物品交互及异常清理。保留 0.118.1 和此前移植内容。

Minecraft 1.20.1 / Forge / Java 17，协议 **7**。

- 文件：`modular-machinery-reborn-0.118.2.jar`
- 来源：`modular-machinery-reborn/build/reobfJar/output.jar`（最终 reobf 输出）
- 字节：1460582
- SHA-256：`9DB22BBCB68CA8F9F42C06899BC8BB18443F72C86FE29E8501631D58DEEE5F4C`

新增预览专项 16、真实 JEI 槽位专项 7，既有回归 3296，共 **3319 项零失败**。最终生产 build/reobf **31s**；203 JSON、中英各 424 键及协议／SRG 检查通过。原有 0.118.1 的配方、AE2、依赖刷新、菜单与反射缓存修复均保留。

未启动 Forge 游戏或进行 OpenGL 视觉验收。JEI runtime 服务和液体调度器在专项中使用相应测试替身；实际物品提示、R/U 操作、流体混合及 GeckoLib 模型／动画仍待实机。未部署、暂存、提交、推送、上传 GitHub 或创建 Release；实例仍为原有 0.118.0。第三方配方适配器、其他魔法及 Flux／DE 之外的能源不扩展。

[中英文预览指南](../../../modular-machinery-reborn/docs/进阶/结构预览.md) · [修复记录](../../../modular-machinery-reborn/docs/归档/版本记录/修复记录-0.118.2.md) · [交接](../../../modular-machinery-reborn/docs/开发/交接-2026-10-23.md)

## English

This local production archive fixes all seven reviewed JEI preview issues. It includes 0.118.1 and earlier work, uses protocol 7, and passed 3319 offline checks plus the production build. SHA-256 and byte size are recorded above. No OpenGL/Forge gameplay acceptance, game deployment, Git staging/commit/push or GitHub publication occurred.
