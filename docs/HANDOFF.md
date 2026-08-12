# AgentDeck 开发交接

- 更新时间：2026-08-12（Asia/Shanghai）
- 主仓库：`HuaWeiQiu/AgentDeck`
- 主分支：`main`
- 交接基线：`bfb2083`（handoff 修复；本交接文档提交在其后）
- 已发布版本：`v0.2.0-beta.8`，标签停在 `b103cba`
- 下一候选版本：`v0.2.0-beta.9`

## 先读结论

1. `main` 是唯一开发事实源。Secure/Lab 已经是同一 Android 工程中的 product flavor，
   `origin/channel/secure` 与 `origin/channel/lab` 仍停在 beta.7，不应从这两个旧分支继续开发。
2. beta.8 Release 已发布四个 ABI/通道 APK，但不包含随后提交的 handoff 排序修复。
   不要移动 beta.8 标签或替换其资产；下一次发布使用 beta.9。
3. 最新远端 Android CI 已全绿：发布基线、稳定性矩阵和 Artifact 上传均通过。
4. 当前优先工作是聊天性能第二阶段。方案已经确定，但代码尚未开始改造。
5. app-server rollout 是聊天记录的唯一持久化事实源。任何性能优化都不得把消息正文双写到
   Room，也不得以窗口淘汰为由删除、裁剪或改写历史。

## 当前产品形态

AgentDeck 是 Android 上聊天优先的本地 Codex 客户端。默认使用 App 私有的 ARM64/x86_64
Ubuntu + Codex 0.147.0 Runtime，不要求用户安装 Termux。Android 通过带一次性 capability
的 `127.0.0.1` WebSocket 连接 `codex app-server`，不重写 Codex Agent 循环。

产品通道：

| 通道 | applicationId | 扩展边界 | 当前验收 |
| --- | --- | --- | --- |
| Secure Beta | `com.agentdeck.app.debug` | Skill、HTTPS Remote MCP；最高 L2 | Android 16 ARM64 真机已验收 |
| Lab Beta | `com.agentdeck.app.lab.debug` | 额外开放本地 stdio MCP 与实验宿主能力；最高 L4 | 自动测试/构建/隔离扫描，未做真机 |

Beta 使用测试签名、R8、资源压缩和依赖 Baseline Profile，不是正式签名稳定版。

## 最近完成的工作

### 受管扩展

- Room 7→8 已加入规范化 Extension、MCP/Skill 配置、工具和会话选择关系。
- 设置页按需进入“扩展”，支持 Skill 导入、远程 MCP、工具发现和每会话选择。
- Remote MCP 支持 None/Bearer；Bearer 由独立 Android Keystore 密文保存，不进入 Room、
  TOML、argv、Runtime 环境或日志。
- Secure 禁用全局/项目/raw TOML MCP，仅允许受管 MCP；未知 server、空 allowlist、越界工具
  和有效配置读取失败均 fail-closed。
- 工具审批显示本地可信服务名、canonical 工具名和参数；完全访问模式仍保留 MCP 审批。
- Skill 使用独立会话快照，两个 app-server 不能读取或修改对方快照。
- Secure APK 不包含 Lab 本地 MCP adapter/Accessibility；Release gate 会扫描 dex/manifest。

完整边界见 [ADR-0013](ADR-0013-MANAGED-EXTENSIONS.md)。不要只在 UI 隐藏高权限能力，
数据策略、启动配置和 source set 隔离都必须保持。

### 聊天与生命周期

- 历史恢复只取最近 50 turn，向上滚动按 25 turn 游标加载。
- 单一父级 `LazyColumn` 按 Markdown 顶层 AST block 虚拟化；流式阶段使用纯文本，完成后在
  单并发后台解析 Markdown；AST LRU 上限为 24 条或 12 MiB。
- 最近一次会话有 120 item / 256 KiB 的有界首屏预览；权威 app-server 页面到达后替换。
- 输入框为 1–5 行、内部滚动，最大 32,000 字符，不会随长文本占满屏幕。
- 后台会话支持审批和 user-input 有界 FIFO、前后台无损 handoff、超时回收和资源释放。
- `bfb2083` 把 handoff marker 放到 Socket Transport 的同一入口队列，修复 marker 越过已经
  到达但尚未转发事件的竞态。不要把 marker 再直接写入下游 Client inbound 队列。

### 文件输入

- PNG/JPEG 使用模型原生图片输入。
- 文本/代码/JSON/YAML/CSV/XML/HTML/log 受控读取。
- PDF、DOCX、XLSX 在私有 Runtime 转换为受限文本 sidecar。
- 单次最多 4 个文件，每个最多 20 MiB；不执行宏、公式、链接、脚本或嵌入对象。

## CI/CD 状态

最新绿色运行：

- Android CI：<https://github.com/HuaWeiQiu/AgentDeck/actions/runs/31531572620>
- 提交：`bfb20830b91f1a2d05c5be988b0c2b0847551251`
- 结果：发布基线、Host 稳定性矩阵和 Artifact 上传全部成功，用时 9 分 12 秒。
- Artifact：`agentdeck-beta-verification`，包含四个 APK、Lint、稳定性报告和 JVM 测试报告。

上一轮 `b103cba` 的 CI 曾在 Lab `CodexRpcClientTest` handoff 用例失败。它暴露的是双队列
真实排序问题，不是单纯 flaky test；修复后远端已经通过。CI 现在会在失败时上传
`android/app/build/reports/tests/` 与 `android/app/build/test-results/`。

当前 workflow 只有 push/PR CI，没有自动创建 GitHub Release 的 CD。发布仍需人工完成：

1. 先在最终源码上通过本地发布验证和真机验收。
2. 推送并等待远端 Android CI 绿色。
3. 再创建不可变 tag 和 prerelease，上传四个最终 APK。
4. 对比本地产物、Release asset digest 和真机安装 APK 的 SHA-256。

不得在 CI 失败时以“本地通过”为理由先发布。beta.8 已经存在，不要重写历史资产。

## 发布与真机证据

beta.8 Release：<https://github.com/HuaWeiQiu/AgentDeck/releases/tag/v0.2.0-beta.8>

- 设备：Vivo V2301A，Android 16，ARM64。
- Secure R8 APK 已覆盖安装，并核对手机 APK 与 Release 前本地产物 SHA-256 一致。
- v2ray/VPN Fake-IP 模式下，DeepWiki 可发现 3 个工具。
- `read_wiki_structure` 会展示服务、canonical 工具、参数和 MCP 专用审批；批准后得到真实
  DeepWiki 业务响应。
- 强停 App、重新进入同一 thread 后再次调用成功，稳定受管 server ID 保持恢复语义。
- Lab 按既定范围未安装真机；x86_64 只有构建和包内容验证，尚未启动 Runtime。

注意：以上真机证据对应 beta.8 的 `b103cba`。最新 `bfb2083` handoff 修复通过本地与远端
自动化，但尚未打成 beta.9 并做真机覆盖安装。

## 性能现状与下一阶段

第一阶段固定真机 Beta 双样本：

| 指标 | 样本 1 | 样本 2 | 说明 |
| --- | ---: | ---: | --- |
| Total PSS | 113,012 KB | 131,352 KB | 平均 122,182 KB |
| Total RSS | 254,000 KB | 276,744 KB | 平均 265,372 KB |
| 卡顿帧 | 1.38% | 0.31% | 两次都低于旧基线 |
| P99 | 23 ms | 13 ms | 旧基线为 46 ms |

这些是同机、固定会话和固定手势的点样本，不是所有设备的承诺。完整原始基线见
[CHAT_PERFORMANCE.md](CHAT_PERFORMANCE.md)。

第二阶段方案见 [chat-performance-phase-2.md](plans/chat-performance-phase-2.md)，状态为
“待实施”。推荐严格按以下顺序执行：

1. P0：先建立 Macrobenchmark、固定 50/300/1000 turn 合成数据、Perfetto trace 和应用自身
   Baseline Profile，得到可重复的改造前基线。
2. P1：item ID 索引、分页级增量时间线投影、可视区 Markdown 调度。
3. P2：最多 8 页或 4 MiB 的有界历史窗口，完成 cursor 重取和滚动锚点恢复。
4. P3：Activity/Diff 父级块虚拟化、最多 2 个空闲 held app-server 的 LRU、低内存回收。
5. P4：Secure ARM64 真机三轮性能/完整性回归，更新性能文档并发布 beta.9。

实施有界窗口前，必须先用真实 Codex 0.147.0 验证 cursor 在新增 turn 和 resume 后的有效期。
页面淘汰只释放 Android 内存；20 页往返后要机器校验全部 item 的 ID、顺序、正文和 patch
哈希一致。

## 下一位接手者的第一批操作

```bash
cd /Users/tanye/AgentDeck
git fetch origin
git status --short --branch
git log -5 --oneline --decorate

export JAVA_HOME="/path/to/jdk-17"
./scripts/verify-release.sh
./scripts/verify-stability-matrix.sh
```

开始性能改造前：

1. 确认 `HEAD` 与 `origin/main` 一致且没有用户未提交改动。
2. 阅读 `docs/plans/chat-performance-phase-2.md`、`docs/CHAT_PERFORMANCE.md`、
   `docs/ADR-0005-NATIVE-CHAT-BRIDGE.md` 和 `docs/ADR-0013-MANAGED-EXTENSIONS.md`。
3. 从 P0 基准工程开始，不要直接重写 `ChatTranscriptRepository`。
4. 每个 P 阶段单独提交，完成测试、审查、修复、复测后再进入下一阶段。
5. 只在需要最终 Secure ARM64 验收时要求用户连接手机；不要在含唯一用户数据的设备上运行
   可能卸载 App 的 `connectedDebugAndroidTest`。

## 关键文件

| 责任 | 文件 |
| --- | --- |
| 聊天 ViewModel 与前后台交接 | `android/app/src/main/java/com/agentdeck/app/ui/chat/ChatViewModel.kt` |
| 后台会话所有权 | `android/app/src/main/java/com/agentdeck/app/data/chat/ChatSessionRegistry.kt` |
| RPC、Socket 和 handoff fence | `android/app/src/main/java/com/agentdeck/app/data/chat/CodexRpcClient.kt` |
| 当前历史内存投影 | `android/app/src/main/java/com/agentdeck/app/ui/chat/ChatTranscriptRepository.kt` |
| Compose 时间线 | `android/app/src/main/java/com/agentdeck/app/ui/chat/ChatScreen.kt` |
| Markdown AST/LRU | `android/app/src/main/java/com/agentdeck/app/ui/chat/ChatMarkdown.kt` |
| 时间线分组投影 | `android/app/src/main/java/com/agentdeck/app/ui/chat/ChatTimeline.kt` |
| Codex 协议与历史分页 | `android/app/src/main/java/com/agentdeck/app/domain/chat/CodexProtocol.kt` |
| 扩展配置与会话计划 | `android/app/src/main/java/com/agentdeck/app/data/extensions/ExtensionRepository.kt` |
| Remote MCP 安全网络层 | `android/app/src/main/java/com/agentdeck/app/data/extensions/SecureMcpNetwork.kt` |
| Android CI | `.github/workflows/android.yml` |
| 发布门禁 | `scripts/verify-release.sh`、`scripts/verify-stability-matrix.sh` |

## 明确的已知边界

- 最新源码尚无 beta.9 Release；README 中“当前源码与 beta.8”会在 beta.9 发布准备阶段一并更新。
- x86_64 Runtime 尚未在 Android 模拟器完成安装、启动、会话和恢复闭环。
- Lab 尚未真机验收，本地 MCP 无已发现工具时保留兼容模式；一旦有工具元数据就启用严格
  allowlist，全部关闭时必须保持 `enabled_tools=[]`，不能 fail-open。
- 正式签名、更多 OEM/Android 版本、低内存、锁屏、进程回收和长时 soak test 仍是稳定版
  阻塞项。
- GitHub Actions 不执行可信的真机帧率门禁；性能指标必须来自固定 ARM64 真机或受控设备农场。

## 不要破坏的约束

- 不回滚或覆盖用户本地改动；工作区非干净时先理解差异。
- 不让 Secure 反序列化或执行 Lab L3/L4 能力。
- 不把 Provider Key、MCP Bearer、capability、聊天正文或附件路径写入日志和测试 Artifact。
- 不把 per-session Skill 放回共享 Codex Home，也不把其他会话快照暴露到通用 Runtime bind。
- 不以 MCP `readOnlyHint` 免除审批，不信任远端 title/message 作为 canonical 工具身份。
- 不在运行中静默热切扩展；扩展变化只对下一连接生效。
- 不用 `mcp_servers={}` 假设可以清空 Codex 深层合并配置。
- 不在新 owner 接管事件流时直接取消旧 Channel collector；必须经过有序 handoff fence。
