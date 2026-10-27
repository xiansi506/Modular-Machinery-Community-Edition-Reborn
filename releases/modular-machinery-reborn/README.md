# Modular Machinery: Community Edition Reborn 版本索引

> **源码交付说明（2026-10-27）**：本次 Git 提交包含 0.118.4–0.118.6 对应源码、文档及归档说明／SHA-256。此索引列的是本地构建记录；JAR 依现有规则不进入 Git 历史，未创建 GitHub Release。下方“未提交／未推送”为各功能实现当轮的历史状态。

> **最新本地归档 0.118.6**：[显示说明](v0.118.6/README.md)，1488995 字节，SHA-256 `0B69905FBA631FE6FDE087B7693C8BD2078F6DE4E4D174C4603741BFD5761736`。新增 29 项专项（另含 200 组布局场景），相关既有 2951 项，共 2980 项零失败；生产 build/reobf、203 JSON 和中英各 427 键检查通过。协议保持 8，配方格式与结算不变，不需要安装 gugu-utils；未启动／部署游戏，画面待实机验收。

> **此前本地归档 0.118.5**：[修复说明](v0.118.5/README.md)，1468546 字节，SHA-256 `3C7160D5926E170F9DBCFA574F53AC4913D1E067EF5F9E54DA32813B0FDB74AC`。新增 35 项专项由红转绿，本轮相关回归 3001 项，总计 3036 项零失败，生产 build/reobf 和资源检查通过。通信协议升为 8，客户端与服务端必须同时更新；协议 7 及更早版本不兼容。本轮未启动／部署游戏，修复后实机待验收；未暂存、提交或推送。

> **此前本地归档 0.118.4**：[修复说明](v0.118.4/README.md)，1468509 字节，SHA-256 `2F29D36B2DCD2213F0C03931FD15403529BCD3BD725AEF48E0D729E08A573848`。新增专项 43 项（另含 2000 组随机整数算术对照），既有回归 3338 项，共 3381 项通过、零失败；生产 build/reobf 与资源检查通过。协议仍为 7。未启动或部署游戏，未暂存、提交或推送本轮修改；实例仍为实查的 0.118.2，真实重载、网络及画面待实机验收。

> **此前本地归档 0.118.3**：[修复说明](v0.118.3/README.md)，1460779 字节，SHA-256 `A4F411F8B165E98D2511708A4798073DCFB153B258884CF2CB0690273BECB7E8`。新增 6 项真实 JEI 布局生命周期回归由红转绿；本轮连同此前预览 23、界面 28、主套件 2669，共 2726 项检查零失败。生产 build/reobf 32s，203 JSON、中英各 424 键及协议／SRG 检查通过。协议仍为 7。本轮未启动游戏、未部署或操作 Git 暂存／提交／推送，修复后画面待实机确认。当前实例实查为 0.118.2，哈希与此前归档一致。

此前修复归档为 [v0.118.2](v0.118.2/README.md)，协议 7；1460582 字节，SHA-256 `9DB22BBCB68CA8F9F42C06899BC8BB18443F72C86FE29E8501631D58DEEE5F4C`。新增预览专项 16、真实 JEI 槽位专项 7，既有 3296 项回归全部通过，共 3319 项零失败；生产 build/reobf 31s，203 JSON、中英各 424 键、SRG 与协议 7 检查通过。未启动游戏或做 OpenGL 视觉验收，未部署、暂存、提交、推送或上传 GitHub；实例仍为实查的 0.118.0。保留 0.118.1 及更早功能，不扩展其他集成。

> **此前本地归档 0.118.1**：[修复说明](v0.118.1/README.md)，协议 7，1443379 字节，SHA-256 `13FAF1323551263F6B5724ADC616124688BC5E1882A8DDABC1AEF533830ACFA8`。新增专项 33、完整套件 2669、既有专项 424、魔法核心 52、真实 API 23、示例解析 6、可选依赖隔离 89 项均零失败（共 3296）；最终生产 build/reobf 38s，203 JSON、中英各 424 键、SRG 与协议 7 检查通过。本轮未实机、未部署或创建 GitHub Release。

> **此前本地归档 0.118.0**：[魔法资源版说明](v0.118.0/README.md)，协议 **7**。1,435,821 字节，SHA-256 `1B70C2C6703414FD55FC52958AD5B8413127DCEF14213C91EE39D04129B5399F`。3259 项离线检查通过；未实机、未部署、未创建 GitHub Release。源码提交交接见 [当前交接文档](../../modular-machinery-reborn/docs/交接-2026-10-23.md)。JAR 按现有忽略规则不进入 Git 历史。

> **此前本地归档**：[0.117.0 能源与特殊渲染](v0.117.0/README.md) · [0.116.0 ME 滚轮](v0.116.0/README.md) · [0.115.0 工厂能量信息](v0.115.0/README.md) · [0.114.0 世界模型隐藏](v0.114.0/README.md)。均为本地构建归档，未创建 GitHub Release。

> **此前本地归档 0.113.0**：[客户端脚本版说明](v0.113.0/README.md) · [SHA256](v0.113.0/SHA256.txt)。协议仍 5，未实机、未部署、未创建 GitHub Release。

> **此前本地归档 0.112.0**：[预览 NBT 版说明](v0.112.0/README.md) · [SHA256](v0.112.0/SHA256.txt)。协议仍 5，未实机、未部署、未创建 GitHub Release。

> **此前本地归档 0.111.0**：[高级脚本版说明](v0.111.0/README.md) · [SHA256](v0.111.0/SHA256.txt)。协议 5，未实机、未部署、未创建 GitHub Release。

> **此前本地归档 0.110.0**：[界面细节版说明](v0.110.0/README.md) · [SHA256](v0.110.0/SHA256.txt)。协议仍 4，未实机、未部署、未创建 Release。

> **此前本地归档 0.109.0**：[供应器机制版说明](v0.109.0/README.md) · [SHA256](v0.109.0/SHA256.txt)。协议 4，未实机、未部署、未创建 GitHub Release。

> **此前本地归档 0.108.0**：[气体功能版说明](v0.108.0/README.md) · [SHA256](v0.108.0/SHA256.txt)。协议 3，未实机、未部署，本轮未创建 GitHub Release。下方旧版本与既有发布记录保留。















本索引列出有归档目录的版本；**旧版本归档永不覆盖、永不删除。** 是否存在对应的 GitHub Release，以备注为准。















| 版本 | JAR | SHA-256 | 备注 |







|---|---|---|---|







| `v0.1.0` | `modular_machinery_reborn-0.1.0.jar` | `10E9C72E185FFDB7C0B3E25D08403E5C4B4FD674832362D6FD0C44A494A28C95` |  |







| `v0.2.0` | `modular_machinery_reborn-0.2.0.jar` | `2A68F2FE9173A0D0A63DB231E95648F3831C6E6E6034ACC6ACBD6268A3AD5732` |  |







| `v0.2.1` | `modular_machinery_reborn-0.2.1.jar` | `1D54DAFDB482039E94CE73ABCFC1ECC799E2DEE0884B1C1FB067B31C78A56260` |  |







| `v0.2.2` | `modular_machinery_reborn-0.2.2.jar` | `36E6D6952BEDBA06FF6E12BBF37AB249969677A2ABE7F122D5898F633FCD3F49` |  |







| `v0.2.3` | `modular_machinery_reborn-0.2.3.jar` | `B06C95374AFE3F825B3A5D64CD789ED1461DADEE929D7A1FBFFE06F0C8D793E3` |  |







| `v0.2.4` | `modular_machinery_reborn-0.2.4.jar` | `3D011196AFB34FFE81D63700E2A16A6063CF10F58DD5C1C554ED65F1B5451C6D` |  |







| `v0.2.5` | `modular_machinery_reborn-0.2.5.jar` | `69A46EF7EA735260F5C4F766914C5134B8ADAC43D588AF1A1FBD33C1A32C6F95` |  |







| `v0.2.6` | `modular_machinery_reborn-0.2.6.jar` | `B8DBFB65313EAD776EAFEEF7D793ADF698D0A6C7215581B09090CEAC597D7185` |  |







| `v0.2.7` | `modular_machinery_reborn-0.2.7.jar` | `45755E03918CA12108E2C21A21B4C94578DB8007100D5B576E51173155F282BC` |  |







| `v0.3.0` | `modular_machinery_reborn-0.3.0.jar` | `427BE0E9CC464CECEAE35CAB6DC90D996C013328FFA9B5E2CE6CF99FB326C0B9` |  |







| `v0.4.0` | `modular_machinery_reborn-0.4.0.jar` | `E93EAE55A2650F9D3DD58CED895B7E9E3E40AC58D1D473552D421E74BDDB42EB` |  |







| `v0.4.1` | `modular_machinery_reborn-0.4.1.jar` | `8DCF6EAEF37C265E878613124DBFEE2904B6BE7AAF5A6E1A2497DE8DEA45A4E1` |  |







| `v0.4.2` | `modular_machinery_reborn-0.4.2.jar` | `E80ED877C38F7D285E07CE3FF8EB1C28157B92464FF8701CD0F2D589DB578720` |  |







| `v0.4.3` | `modular_machinery_reborn-0.4.3.jar` | `706DC656742E73DB50F9AF43F76EE6AC372A80402B4026B8E79784D8FCD34212` |  |







| `v0.4.4` | `modular_machinery_reborn-0.4.4.jar` | `059E369E1053E1C22EFB82BFBE5EE93F7FFC49E086AB489A42E66A499CFA407A` |  |







| `v0.5.0` | `modular_machinery_reborn-0.5.0.jar` | `8B55FD0AA19DB78C2BD3DA525FCD57EF529FBBC556505A4E4D41365410F7A37A` |  |







| `v0.5.1` | `modular_machinery_reborn-0.5.1.jar` | `0D221AB864D7E996FFEE45D1BEB7B55333A1813C0AD2A195FADE4CED8E4FF91A` |  |







| `v0.6.0` | `modular_machinery_reborn-0.6.0.jar` | `F785B535A8A36ADF055C839A0003841FA96448BCBDE0CED0EF095B3C802C1F9B` |  |







| `v0.7.0` | `modular_machinery_reborn-0.7.0.jar` | `313CAA80B72DB1871BFA4E36416EDCACB6267A68BD2C570795780EBA034F5D4E` |  |







| `v0.8.0` | `modular_machinery_reborn-0.8.0.jar` | `FFBDDCC023FDC70160233D043E0CDA8313DE5EF4FFB672C16C82312C3737AA82` |  |







| `v0.9.0` | `modular_machinery_reborn-0.9.0.jar` | `400572F38860143782FF287D3CD2286AB2C030C5EE3BF7B27F6A159C1CDADD0A` |  |







| `v0.10.0` | `modular_machinery_reborn-0.10.0.jar` | `002C4CE28F307DCD4FDAC04D0B81FF4279501A9405CC1B0661E0CC6230A82216` |  |







| `v0.11.0` | `modular_machinery_reborn-0.11.0.jar` | `3E574BB9128E3D7B0871DDEE7439844DC57D97CEAE314611F06D7682C4FFB761` |  |







| `v0.12.0` | `modular_machinery_reborn-0.12.0.jar` | `E4CD45993CE8AFB99CC626898192392E21657EA1FF240188B3EF87726924E84D` |  |







| `v0.13.0` | `modular_machinery_reborn-0.13.0.jar` | `1DA9D9BB47A71A33458FE2382D0ED370BF6E93052C95711676743F021FB19C6E` |  |







| `v0.13.1` | `modular_machinery_reborn-0.13.1.jar` | `91037C6056762FB4FE100B55E7EC77C5C964366BF42EC3F3D3E6A97987A81A54` |  |







| `v0.14.0` | `modular_machinery_reborn-0.14.0.jar` | `86DF16CF5988F640611EE90CD0915EF24C4AD5EDF176691A499782A39B446A5B` |  |







| `v0.15.0` | `modular_machinery_reborn-0.15.0.jar` | `BBF1BDB52CE0F8E7D23222B1E3337F92DA04269C9AD23364ADBFEB8A8B8968F3` |  |







| `v0.15.1` | `modular_machinery_reborn-0.15.1.jar` | `43E7BFF4773EE4C48E9CC55BB8167EF241D2C7700013D964AF88CA6923C0CC6A` |  |







| `v0.15.2` | `modular_machinery_reborn-0.15.2.jar` | `F56F18B32A0CC021F1D3B4819047F5B938D938391FC66644E39A4102C412FA4F` |  |







| `v0.15.3` | `modular_machinery_reborn-0.15.3.jar` | `C75F12404B330AF5CE00286DEAD8C03E25969879A8AEFFA80053FA7C64C5CA60` |  |







| `v0.15.4` | `modular_machinery_reborn-0.15.4.jar` | `85B076291F1CCB5C86E47B7217450EDBA912EF237463F422ABE6079EB5400119` |  |







| `v0.16.0` | `modular_machinery_reborn-0.16.0.jar` | `2076A05A9F4BA75E6F9BBE9CE33F52D0B5E063C4602C5081C38360AFB39B7F5F` |  |







| `v0.16.1` | `modular_machinery_reborn-0.16.1.jar` | `625C4D203F9BA0C340AF01CF29E9265145C54C6DD1A615B624162AE60EB3403A` |  |







| `v0.16.2` | `modular_machinery_reborn-0.16.2.jar` | `5149D50427115CE1966BFC8DA7063C78AEFCA6FA171CB31638140CF70405A8C6` |  |







| `v0.16.3` | `modular_machinery_reborn-0.16.3.jar` | `15DEAA8456DB6A1197916C6A5183020D0102E7D3443318D7468387D913AAF789` |  |







| `v0.17.0` | `modular_machinery_reborn-0.17.0.jar` | `5F5B9A073425B7F1145BBB4B8CE3B9CE7D189E494281E621FC30C8DDBC6C85E7` |  |







| `v0.17.1` | `modular_machinery_reborn-0.17.1.jar` | `7ADB9CECC19C7DF102C8E03C3F9FCEDD5EDC22000EFEF427CEC43464C31391B9` |  |







| `v0.18.0` | `modular_machinery_reborn-0.18.0.jar` | `F387D918159EC1DD314B1EACF69ECD371231B80D2BAC0B176D5808DF07D27897` |  |







| `v0.19.0` | `modular_machinery_reborn-0.19.0.jar` | `B8DDF9A2061F565CF596162668FAFDB4A955C16487D683700C48827A7550E1DA` | **切勿使用：未重混淆，会崩溃** |







| `v0.19.1` | `modular_machinery_reborn-0.19.1.jar` | `7CE490615D643509C8CA0BBF605208B5402A10C5E73D755F636DD83193469B4D` |  |







| `v0.20.0` | `modular_machinery_reborn-0.20.0.jar` | `5D805EBD833E7C88606F29E9DEA608A1A0EAD8B6F2F9F6FC342C53A81784C5C6` |  |







| `v0.21.0` | `modular_machinery_reborn-0.21.0.jar` | `60FE0C5E26A03E173F94C160C68428AAC1BFB2520F3E8A703DC5334513312103` | **切勿使用：两个 COMMON 配置冲突，启动崩溃** |







| `v0.21.1` | `modular_machinery_reborn-0.21.1.jar` | `003A4964FF2E1EC2712BE7A26D54AFFAF5B8A11E4F7684E921753396EE8CDF09` |  |







| `v0.22.0` | `modular_machinery_reborn-0.22.0.jar` | `C709B55B63E2E50C8055A722A8E9956FB437D23970B4A2841B3F7A08A25E037B` |  |







| `v0.23.0` | `modular_machinery_reborn-0.23.0.jar` | `6F9DA7435B6C25D3E48B0FC0C4A809DF582ABD418F0B8DA1D3DC9B5C020B064A` |  |







| `v0.24.0` | `modular_machinery_reborn-0.24.0.jar` | `27585A31B13C5F3FFA7D1B4290A757DBDC09A80ABA026A87239E3C46B70F7483` | **切勿使用：控制器界面哨兵值崩溃** |







| `v0.24.1` | `modular_machinery_reborn-0.24.1.jar` | `DF754EA0A907E799AD9BD80E91B8E727E194F3079E785432CC05694A772E0B1B` |  |







| `v0.24.2` | `modular_machinery_reborn-0.24.2.jar` | `A336D8A8F918BF423AD59E98DD6586F48DF669079E134B27D87BCEE01D61C1DA` |  |







| `v0.24.3` | `modular_machinery_reborn-0.24.3.jar` | `C7B52AFC65850ED6EF6B1C9454138C9F4EA84470B421EAC352AE0F5EDCE5CCC2` |  |







| `v0.24.4` | `modular_machinery_reborn-0.24.4.jar` | `98FE64A15394F3B77322395F45809489A8679E02CD1774581B538558B65A714B` |  |







| `v0.24.5` | `modular_machinery_reborn-0.24.5.jar` | `B1F09CE0222EC7F270A270FBE617E7D157C9608E25651B34DF7A2E1256219CB4` |  |







| `v0.25.0` | `modular_machinery_reborn-0.25.0.jar` | `F9C8E2E502EC1EBCD9931E4D09B6E3FBD18AD96ABCD8DB57654B730C993B4A01` | 第 1 波①：ailure-action 消费 |







| `v0.26.0` | `modular_machinery_reborn-0.26.0.jar` | `F018AF91FE6D347B64F1555C176F4110DE886B41D4A19F3209EE9B15CE13D6E2` | 第 1 波②：ingredient_array_input 需求 |







| `v0.27.0` | `modular_machinery_reborn-0.27.0.jar` | `47E3B2AF654FB3EEFB7BEE0ADD71DDD1F0725271F3CE20E1F2ED09C538A0A608` | 第 1 波③：MOC modularcontroller 命名空间兼容 |







| `v0.27.1` | `modular_machinery_reborn-0.27.1.jar` | 790D99984E2BB11048EB1D19FB1E0ED4F07D99C54BBFA8DE84B179A63175F034 | 修 KubeJS 插件声明目录（v1 实机发现的缺陷） |






| `v0.28.0` | `modular_machinery_reborn-0.28.0.jar` | 606BFC43025CC3242BB494423FBDA46DBD42A542EA37D2E328CE435BAF9D009F | v2a：KubeJS 机器定义（核心字段）；**实机确认通过** |





| `v0.28.1` | `modular_machinery_reborn-0.28.1.jar` | 6B0D00C49C5978F1A326481139BC2565BC38EAAC8BC39F8DD8CBABE5C8CC6972 | 修 KubeJS 机器 API 绑定（须用 EventGroupWrapper）；复现器首次可加载 KubeJS；实机确认通过 |



| `v0.28.2` | `modular_machinery_reborn-0.28.2.jar` | 288CA92200B7F4020C37E5F4CB5D1503B1B38D6BEA043FE71621B6C527A7169C | 修 `part` 重载歧义（改名 `parts`）；数组写法原本就是坏的；实机确认通过 |



| `v0.29.0` | `modular_machinery_reborn-0.29.0.jar` | 745E42FDCD8E9079FD668B555248C684652617560C753CD4DD7AA2B71C6D06E4 | 构造工具（忠实的选区+导出；原版没有建造这半，D21）；**实机确认通过** |


| `v0.30.0` | `modular_machinery_reborn-0.30.0.jar` | D9C72C6E329544D0B407A52B4163EADD9BBF9DE35F80537AB23B98A25B10DE6B | **首个公开版本**；功能上等同 0.29.0；**仅文档引导分发**；⚠️ **已被 0.30.1 取代（许可证误标）**；开发中 |

| `v0.30.1` | `modular_machinery_reborn-0.30.1.jar` | 724D79FB2AD4A024B33C89D109EE5CED06191932ADFCDABEFDB035FEF76830F7 | 修正 0.30.0 的许可证误标（MIT→GPL-3.0）与署名；功能等同 0.30.0；⚠️ **已被 0.31.0 取代**；开发中 |
| `v0.31.0` | `modular_machinery_reborn-0.31.0.jar` | C61825E7DEBD2CBCCA33C4F9C5EF59EFF69D3AA7C25F600FDBADB51A61943C21 | **历史公开版本记录**（0.107.x 尚未建 GitHub Release）；KubeJS 可写完整机器定义（11 个字段）；修复社区反馈的「方块挖得极慢且什么都不掉」（4 处静默缺陷：标签命名空间、缺战利品表、变体分池、生成包声明与资源 id 形状）；⚠️ 开发中 |
| `v0.107.1` | `modular-machinery-reborn-0.107.1.jar` | `82BBFAB80682C03067DFEF7B83D5C6935A38D136A2D7C1436E1D1D4C091D133D` | 对照 1.12.2 审查修复 27 项；完整离线套件 2669 PASS；未实机、未部署；[记录](../../_audit/fix-2026-10-25/修复记录.md) |
| `v0.107.2` | `modular-machinery-reborn-0.107.2.jar` | `4881CD92D7999AF542F27B3A2B58B56681C963E0A3687E9071930EBA3A5A44AF` | 气体注入不再清空已有流体；结构旋转同步方块状态；专项 22＋29，完整套件 2669 PASS；未实机、未部署；[记录](../../_audit/fix-0.107.2/修复记录.md) |
| `v0.107.3` | `modular-machinery-reborn-0.107.3.jar` | `F814CD1D966B5E77C572CE0295EFAA66D5A1AB0973A09516A9E2A8664AE52603` | 工厂并行分配、催化剂支付绑定、单机宿主同步、访客退出清理；专项 18＋29＋22，完整套件 2669 PASS；历史源码版本；未实机、未部署；[记录](../../_audit/fix-0.107.3/修复记录.md) |








> 每个版本目录在本机应包含：版本 JAR、该版本 README.md、SHA256.txt。提交到 Git 的只有 README.md 与 SHA256.txt，JAR 走 GitHub Releases。



## 二进制分发



各版本的 *.jar 通过 **GitHub Releases** 分发，**不入 git 历史**（每个版本目录仍保留 README.md 与 SHA256.txt 作为校验凭据）。


