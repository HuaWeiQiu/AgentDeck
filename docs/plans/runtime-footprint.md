# 计划：Runtime 与安装包体积拆分

- 状态：**V1–V3 已落地**；Codex rootfs/下载在 `runtimes/codex/`，共享用户数据在 runtime 根。
  **V3.1（本交接）**：`RuntimeLayoutContract` + JVM 测试锁死目录边界。**V4 真接入仍待各 CLI 单独立项**。
- 适用版本：布局与设置页已在 `0.2.0-beta.10` 之后的 main；下次预发布说明里写清「按 CLI 分目录」。
- 动机：一份 Ubuntu + Codex rootfs 约 116 MB 下载、安装后近 1.1 GB。不能再「一次下全套」。
- 关联：启动速度与 warm/磁盘缓存见 [`agent-startup-acceleration.md`](agent-startup-acceleration.md)（compile cache 目录应落在各 CLI 树内并随删除清理）。

## 结论

日常 Secure 只准备 **当前要用的那一条 CLI**。默认仍是 Codex。其它运行时按需下载，互不混装，
卸载某一条不影响会话元数据和密钥。

APK 本身继续只带 PRoot/loader/talloc 和配方清单，不把 rootfs 打进包。

## 非目标

- 不把 Claude / DeepSeek / pi 的实现做完才开始拆体积（已拆完布局）。
- 不把多套 rootfs 预置进 APK。
- 不把 Lab 无障碍或语音模型打进 Secure。
- 不靠「压缩同一份 116 MB」假装解决多 CLI 问题。
- **无设备时不假装完成冷安装验收**；用 JVM 契约 + 配方 `available: false` 守门。

## 用户看到的完成态

1. 第一次安装：只问「先准备 Codex」，下载还是现在那一份 Codex 组件，不再暗示还有别的大包。
2. 设置里「运行环境」按 CLI 列出：Codex 已就绪 / DeepSeek 未下载 / Claude 未下载。
3. 点「准备 DeepSeek」才开始下 DeepSeek 那一份；失败不影响已装好的 Codex。（**V4**）
4. 可以单独删除某一个运行时，会话名和人设还在。
5. 标准模式不出现镜像域名、PATH、exit code。

## 磁盘布局（事实源）

根：`noBackupFilesDir/agentdeck-runtime/`（常量 `RuntimeLayoutContract.RUNTIME_ROOT_NAME`）。

| 路径 | 归属 | 删除 Codex 时 |
| --- | --- | --- |
| `runtimes/codex/rootfs-<releaseId>/` | Codex 组件 | 删除 |
| `runtimes/codex/.rootfs-<releaseId>.staging/` | 安装暂存 | 删除 |
| `runtimes/codex/downloads/` | Codex 下载缓存 | 删除 |
| `runtimes/<other-cli>/...` | 预留（V4） | 不碰 |
| `codex-home/` | 共享用户/配置侧数据 | **保留** |
| `projects/` | 项目目录 | **保留** |
| `state/` · `tmp/` | 运行状态/临时 | 状态保留；tmp 可清 |
| `extensions/packages` · `extensions/sessions` | Skill 包与会话快照 | **保留** |

旧版把 rootfs 放在 runtime 根下；`EmbeddedRuntimePaths.migrateCliLayout()` 在 ready-check /
`ensureHostLayout` 时迁到 `runtimes/codex/`。路径字符串只应来自 `RuntimeLayoutContract`，
避免 V4 再硬编码分叉。

实现入口：

- 契约：`domain/runtime/RuntimeLayoutContract.kt`
- 路径：`data/runtime/EmbeddedRuntimePaths.kt`（`cliRootFor`、`removeCodexRuntime`）
- 库存 UI：`RuntimeInventory` + `RuntimeEnvironmentScreen`
- Catalog：`RuntimeCliCatalog`（仅 Codex `available=true`）
- 占位 recipe：`assets/recipes/{deepseek-harness,pi,claude-code}.yaml` 且 `available: false`

## 技术切法

| 层 | 做法 |
| --- | --- |
| 配方 | 每个 CLI 一份 recipe：自己的制品、校验、磁盘下限、apt 依赖 |
| 存储 | `runtimes/<cli-id>/` 分目录，禁止共用一个 rootfs 当所有 CLI 的家 |
| 下载 | 复用现有续传、看门狗、国内/国际换源；进度按「正在准备 Codex」这种人话 |
| 启动 | 按会话绑定的 recipeId 选目录，找不到就引导去准备，不偷偷下别的 |
| APK | 继续 ABI 拆分；清掉主路径用不到的资源 |
| 语音 | Vosk 模型保持按需，不进默认首次下载 |

## 阶段

### V1. 量清楚再砍 — 完成

- 默认必须有：PRoot 组件 + 当前 Codex rootfs。
- 可按需：其它 CLI、语音模型、调试夹具。

### V2. Codex 单独成套 — 完成

- 安装路径明确为 Codex runtime（`runtimes/codex`）。
- 设置页按 CLI 显示状态和占用。
- 删除 Codex runtime 不删 Room 会话/人设/备份/共享 home。

### V3. 第二个 CLI 的空位 — 完成

- recipe + 目录约定为 DeepSeek Harness / pi / Claude Code 留好，不预下载。
- 未实现的 CLI 显示「即将支持」，点下去不占磁盘。

### V3.1. 目录契约 JVM 化 — 完成（本交接，无设备）

- `RuntimeLayoutContract` 统一相对路径。
- 测试：`RuntimeLayoutContractTest`、`RuntimeLayoutTest` 断言 CLI 树与共享用户数据不交叉，
  且 catalog placeholder 仍不可下载。
- 验收命令（无需手机）：

```bash
cd android
./gradlew :app:testSecureDebugUnitTest --tests 'com.agentdeck.app.domain.runtime.RuntimeLayoutContractTest' --tests 'com.agentdeck.app.data.runtime.RuntimeLayoutTest' --tests 'com.agentdeck.app.domain.runtime.RuntimeCliCatalogTest'
```

### V4. 真接入时按需下 — 未开始

每接一条 CLI 单独开计划，最低交付：

1. `available: true` 的 recipe + 校验过的制品 URL/sha256/size。
2. `EmbeddedRuntimePaths`（或后继）按 `cliId` 安装到 `runtimes/<cliId>/`，**禁止**写入 Codex 树。
3. 启动路径按 recipeId 选 rootfs；PATH/home 与 Codex 隔离（共享的只有明确列出的用户数据目录，若需要）。
4. 设置页「准备 / 删除」对该 CLI 生效；删除不碰其它 CLI 与共享用户数据。
5. Secure 扫描与 JVM 测试覆盖「不会误下第二条」「删除隔离」。

**当前禁止**：在未单独立项前把 placeholder 改成可下载，或共用 Codex rootfs 装第二个二进制。

## 无设备验收清单（现在就能勾）

- [x] Catalog 仅 Codex `available`
- [x] Placeholder recipe `available: false`
- [x] 磁盘相对路径契约与「删除不伤用户数据」测试
- [x] 设置文案：即将支持 / 不占下载
- [ ] 冷安装只下一份 Codex（需真机或模拟器）
- [ ] 删除 Codex 后 Room 会话与人设仍在（需真机；逻辑上 `removeCodexRuntime` 已不碰共享目录）
- [ ] Secure APK 无 Lab 无障碍（继续由 `verify-release.sh` 守）

## 明确往后放

具体接 DeepSeek Harness、pi、Claude Code 的协议与认证，各自单独立项。本计划只保证它们到来时
不必再下一整份「全家桶」，且目录不会再搬一次。
