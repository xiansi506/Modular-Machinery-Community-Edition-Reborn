# 0.118.3 本地修复归档

修复 JEI 定时清除显示覆盖后，结构预览材料表全部变成控制器或同时轮换成同一物品的问题。接入 JEI 显示材料更新回调，并校验槽位真正显示的物品和数量，保留 0.118.2 的预览和此前移植功能。

Minecraft 1.20.1 / Forge / Java 17，协议 **7**。

- 文件：`modular-machinery-reborn-0.118.3.jar`
- 来源：`modular-machinery-reborn/build/reobfJar/output.jar`
- 字节：1460779
- SHA-256：`A4F411F8B165E98D2511708A4798073DCFB153B258884CF2CB0690273BECB7E8`

新增 6 项真实 JEI 布局生命周期回归由红转绿；与预览 23、界面 28、主套件 2669 共 **2726 项检查通过**。本轮没有重复全部 3319 项历史检查。生产 build/reobf **32s**；203 JSON、中英各 424 键及协议／SRG 检查通过。

没有启动游戏或验证修复后的 OpenGL 画面，没有部署、暂存、提交、推送或上传 GitHub。实例仍为用户已安装的 0.118.2；原包哈希已核对并保持不变。

[中英文修复记录](../../../modular-machinery-reborn/docs/修复记录-0.118.3.md) · [预览指南](../../../modular-machinery-reborn/docs/结构预览.md) · [交接](../../../modular-machinery-reborn/docs/交接-2026-10-23.md)

## English

Restores correct preview materials after JEI clears display overrides during ingredient cycling. Six regressions execute the real JEI layout constructor and tick lifecycle, covering focused/unfocused views, dynamic counts, selected alternatives and recipe history. This patch passed 2726 focused checks and a production reobf build. No repaired gameplay acceptance, game deployment or Git staging/commit/push/publication occurred. Protocol 7 and earlier features remain included.
