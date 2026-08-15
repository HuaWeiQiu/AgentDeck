# AgentDeck 开发交接

- 更新时间：2026-08-14（Asia/Shanghai）
- 主仓库：`HuaWeiQiu/AgentDeck`
- 主分支：`main`
- 交接基线：聊天性能第二阶段已落地（P0–P3 + 真机 gfxinfo）
- 已发布版本：`v0.2.0-beta.8`，标签停在 `b103cba`（不要移动）
- 当前源码版本：`v0.2.0-beta.9`（`versionCode=14`）；GitHub Release 尚未打标签

## 先读结论

1. `main` 是唯一开发事实源。Secure/Lab 已经是同一 Android 工程中的 product flavor，
   `origin/channel/secure` 与 `origin/channel/lab` 仍停在 beta.7，不应从这两个旧分支继续开发。
2. beta.8 Release 已发布四个 ABI/通道 APK，但不包含随后提交的 handoff 排序修复。
   不要移动 beta.8 标签或替换其资产；下一次发布使用 beta.9。
3. 最新远端 Android CI 已全绿：发布基线、稳定性矩阵和 Artifact 上传均通过。
4. 当前优先工作是聊天性能第二阶段。P0–P3 已落地并通过双通道 JVM（337×2）；
   剩余工作全部在真机侧：Secure ARM64 一次性设备采集改造前后基线（3 轮）、
   PSS 平台化确认和发布门槛写入。官方性能数字只认 Secure Beta，
   Lab 共享同一聊天栈但不作为发布门槛。
5. app-server rollout 是聊天记录的唯一持久化事实源。任何性能优化都不得把消息正文双写到
   Room，也不得以窗口淘汰为由删除、裁剪或改写历史。
6. Lab 手机 UI Agent 的完整方案已经确定，但尚未开始实现；默认排在聊天性能第二阶段之后，
   且永久不进入 Secure APK。

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

注意：以上 MCP 真机证据对应 beta.8 的 `b103cba`。beta.9 源码已升到
`versionCode=14`，聊天性能真机数字见 `docs/CHAT_PERFORMANCE.md`；GitHub Release
标签尚未打。

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

第二阶段方案见 [chat-performance-phase-2.md](plans/chat-performance-phase-2.md)。P0–P3
已全部落地，双通道 JVM 337×2 通过，编译门禁绿色。剩余工作全部需要真机：

1. P0 基线采集：在一次性 Secure ARM64 设备上用 `scripts/verify-chat-performance.sh`
   采集 3 轮改造后基线（脚本拒绝装有 Lab 包或持有用户数据的日常机）。
   注意：Vivo/Funtouch 会丢 profileinstaller 广播，macrobenchmark 已改用
   `Partial(baselineProfileMode = Disable, warmupIterations = 3)`；USB adb install
   会弹人工确认框，批量采集时先确认手机旁有人或通过设置放行。
   **血的教训（2026-08-14）**：永远不要 kill 正在跑的 `connectedAndroidTest`/
   macrobenchmark gradle 任务——UTP 清理会**连带卸载被测 App**，用户数据（对话
   rollout + Room 里的角色身份）全部丢失。当天因此丢了两次。要中止测试就用
   `adb shell am force-stop` 停设备侧进程，或等它自己结束。
2. P2 真机确认：连续上翻 20 页观察 PSS 平台期；39 页往返哈希一致性已由 JVM 机器校验
   （`ChatPerformancePhase2Test`）覆盖。
3. P3 真机确认：低内存场景下空闲 held session 释放、回会话 thread resume 正常。
4. P4：V2301A gfxinfo 数字已写入 `docs/CHAT_PERFORMANCE.md`，随 beta.9 源码发布。

实施有界窗口前要求的 cursor 有效期验证，改造后只能在真机 Runtime 上最终确认：
JVM 已按“cursor 在 app-server 生命周期内稳定”建模，重连会整体重建窗口。
页面淘汰只释放 Android 内存；20 页往返后要机器校验全部 item 的 ID、顺序、正文和 patch
哈希一致。

## Lab 手机 UI Agent 计划

完整方案见 [lab-ui-agent.md](plans/lab-ui-agent.md)，状态为“待实施”。它参考 Android Action
Kernel 的界面树压缩和 observe/action/re-observe 流程，但继续由 Codex app-server 负责 Agent
loop，通过现有 Host Tool Broker 调用 Lab-only AccessibilityService，不依赖 ADB、Python 或云端
控制服务。

关键边界：

- Secure 不显示入口、不打包 Service/Executor/内置 Skill，策略层继续拒绝 L3。
- Lab 默认关闭，任务必须绑定 conversation、instance、允许 App、TTL 和动作预算。
- 优先使用可验证的节点动作；MVP 不开放任意坐标手势、截图推理或 Shizuku 组合动作。
- 密码、验证码、银行卡、支付 PIN、助记词、私钥和生物认证即使在 Lab 也不允许自动读取或
  输入；进入相关页面必须暂停，由用户亲自完成。
- 每次写动作只使用当前短时 snapshot，执行后强制作废并重新观察；单设备同时只有一个 UI
  automation owner。

推荐顺序是聊天性能 P0–P4 → UI Agent U0–U4 → Lab ARM64 真机 U5 → 单独决定手势/截图 U6。

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
3. P0 代码已在本交接后的提交中；下一步是 Secure 真机基线，或审查后进入 P1。
   不要在没有 JVM 哈希和编译门禁的情况下直接重写 `ChatTranscriptRepository`。
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
| P0 合成数据与指纹 | `ChatPerformanceFixtures.kt`、`ChatTranscriptIntegrity.kt` |
| P0 Secure 基准界面 | `android/app/src/secure/java/com/agentdeck/app/ui/chat/ChatPerformanceBenchmarkActivity.kt` |
| P0 Macrobenchmark | `android/macrobenchmark/`；`scripts/verify-chat-performance-compile.sh`、`scripts/verify-chat-performance.sh` |
| Compose 时间线 | `android/app/src/main/java/com/agentdeck/app/ui/chat/ChatScreen.kt` |
| Markdown AST/LRU | `android/app/src/main/java/com/agentdeck/app/ui/chat/ChatMarkdown.kt` |
| 时间线分组投影 | `android/app/src/main/java/com/agentdeck/app/ui/chat/ChatTimeline.kt` |
| Codex 协议与历史分页 | `android/app/src/main/java/com/agentdeck/app/domain/chat/CodexProtocol.kt` |
| 扩展配置与会话计划 | `android/app/src/main/java/com/agentdeck/app/data/extensions/ExtensionRepository.kt` |
| Remote MCP 安全网络层 | `android/app/src/main/java/com/agentdeck/app/data/extensions/SecureMcpNetwork.kt` |
| Lab 手机 UI Agent 方案 | `docs/plans/lab-ui-agent.md` |
| Android CI | `.github/workflows/android.yml` |
| 发布门禁 | `scripts/verify-release.sh`、`scripts/verify-stability-matrix.sh`、`scripts/verify-chat-performance-compile.sh` |

## 明确的已知边界

- 源码版本已是 beta.9；GitHub Release 标签待打，打完后即可覆盖安装四个 ABI/通道 APK。
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
- 不因 Lab 通道开放密码、验证码、银行卡、支付、生物认证或 AgentDeck 自身审批的自动操作。
