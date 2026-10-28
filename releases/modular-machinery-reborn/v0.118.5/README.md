# 0.118.5 魔法配方崩溃修复

修复魔力、自然灵气、LP 与恶魔意志需求漏写输入／输出方向，导致复制配方或同步配方时缓冲区越界崩溃的问题。

新增 35 项专项由红转绿，本轮相关回归 3001 项，总计 3036 项零失败，生产 build/reobf 和资源检查通过。

**协议 8，两端同时升级，协议 7 及更早不兼容。** 配方 JSON／存档格式不变。未实机、未部署、未创建 GitHub Release。

- 文件：`modular-machinery-reborn-0.118.5.jar`，1468546 字节。
- SHA-256：`3C7160D5926E170F9DBCFA574F53AC4913D1E067EF5F9E54DA32813B0FDB74AC`。
- 来源：`modular-machinery-reborn/build/reobfJar/output.jar`。

[中英文修复记录](../../../modular-machinery-reborn/docs/归档/版本记录/修复记录-0.118.5.md) · [交接](../../../modular-machinery-reborn/docs/开发/交接-2026-10-23.md)。
