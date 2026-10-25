# releases/ —— 版本发布归档

这里按模组、按版本号存放发布元数据：每个版本目录保留该版本的 **README.md** 与 **SHA256.txt**，用于核对构建件；二进制 jar 通过 **GitHub Releases** 分发，**不入 Git 历史**。

## 目录结构

```
releases/
├── modular-machinery-reborn/
│   ├── README.md          ← 版本索引
│   ├── v0.107.3/
│   │   ├── README.md
│   │   └── SHA256.txt
│   └── ...
└── <其他模组>/
    └── v<版本号>/
```

## 归档规则

每次构建发布新版本时：

1. 修改 `gradle.properties` 里的 `mod_version`。
2. 构建：`cd modular-machinery-reborn; ./gradlew build`（或 `gradlew.bat build`）。
3. 从 **`build/reobfJar/output.jar`** 取正式产物；不要使用 `build/libs` 下的开发期 jar。
4. 计算字节数与 SHA-256，写入 `releases/<模组名>/v<版本号>/SHA256.txt`，并写该版本 README。
5. 在 `releases/<模组名>/README.md` 版本索引中登记。
6. 需要公开二进制时，另外创建 **GitHub Release** 并上传 jar；jar 本身不提交到 Git。

> 旧版本归档**不要删**。如果新版本出问题，随时可以按版本目录中的 SHA-256 找回对应构建件。
>
> 发布目录存在不等于已经公开：以 GitHub Releases 与版本索引中的说明为准。
