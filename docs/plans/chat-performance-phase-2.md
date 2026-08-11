# 计划：聊天性能第二阶段

- 状态：待实施
- 适用版本：`0.2.0-beta.9` 及后续版本
- 范围：聊天历史加载、时间线投影、Markdown 渲染、后台会话资源和性能门禁
- 既有基线：[聊天性能基线](../CHAT_PERFORMANCE.md)

## 背景

第一阶段已经完成单一父级 `LazyColumn`、Codex 游标分页、流式状态隔离、后台 Markdown
解析、AST LRU、最近会话首屏预览、R8 和依赖 Baseline Profile。Vivo V2301A 的最终
Beta 双样本平均 Total PSS 为 122,182 KB，快速滚动卡顿帧为 1.38% / 0.31%。

当前剩余成本不在“是否使用虚拟列表”，而在虚拟列表背后的数据和投影：

1. `ChatTranscriptRepository` 会保留所有已经向上加载的正文，内存随历史页数增长。
2. 消息或 Markdown 文档变化时，`groupChatTimeline` 会重新遍历已加载历史并重新生成投影。
3. `CodexProtocol.upsert` 通过线性查找和列表复制更新消息，长会话的工具事件会放大 GC。
4. 展开的处理过程、Diff 和超长代码仍可能在单个父列表项中一次布局大量内容。
5. 空闲 app-server 会话的整机成本高于单纯的 Compose 对象，需要独立预算。

## 目标

1. 快速滑动卡顿帧在固定真机场景稳定低于 1%。
2. 60 Hz 设备固定场景达到 P95 小于 16.7 ms，P99 小于 24 ms。
3. 连续读取 20 页后 Android PSS 进入平台期，不再随页数线性增长。
4. 300 turn 固定场景的 Android PSS 控制在 100–130 MB；同时单独记录 Runtime 子进程。
5. 页面淘汰、重新获取、进程恢复和实时消息合并后，消息 ID、顺序和正文完全一致。
6. 输入、流式回复、工具审批和后台会话的响应性不能因性能优化退化。

这些数值是同一台固定设备、固定 R8 Beta、固定数据集的回归门槛，不外推为所有设备的
绝对承诺。

## 非目标

- 不把 app-server rollout 复制到 Room，不建立第二份持久化聊天事实源。
- 不用截图、位图或纯文本替代 Markdown 语义。
- 不嵌套纵向懒列表，不以破坏滚动手势和可访问性换取局部性能。
- 不淘汰正在流式回复、等待审批、等待用户输入或正在执行工具的会话。
- 不改变附件、凭据、Skill 和 MCP 的安全边界。

## 架构决策

### 1. 有界分页窗口

将 `ChatTranscriptRepository` 升级为 `PagedTranscriptStore`：

- 初始页继续读取最近 50 个 turn，旧历史继续每页读取 25 个 turn。
- 每页保存请求 cursor、next cursor、稳定 item IDs、估算字符数和不可变 items。
- 活跃窗口默认最多 8 个历史页或 4 MiB 正文，实时 tail 单独保留，不计入可淘汰页。
- UI 通过 `LazyListState.layoutInfo` 上报当前可见稳定 key；只淘汰距离可见区最远的完整页。
- 被淘汰页只保留轻量 page descriptor。再次接近边界时用原 cursor 重新获取。
- 淘汰或插入顶部页前保存首个可见 item key 与 offset，提交后恢复锚点，禁止滚动跳变。
- thread 切换、重连和迟到响应继续使用 request generation，旧请求不能覆盖新窗口。

实施前必须用真实 Codex 0.147.0 验证 cursor 在新增 turn 和 resume 后仍可重放。若协议不
保证跨连接 cursor 稳定，descriptor 只在当前 app-server 生命周期内复用；重连后从最新页
重建游标链。rollout 始终保留在 Codex，历史完整性不依赖 Android 内存窗口。

### 2. 分页级增量时间线投影

用 `TimelinePageProjection` 代替每次重新生成整条扁平列表：

- 按 `itemId + contentRevision + markdownRevision` 缓存单个 item 的投影。
- 页面只在自身 items 或对应 Markdown 文档变化时重建。
- `LazyListScope` 直接按页发出带稳定 `key` 和 `contentType` 的 entries，不创建全历史副本。
- 实时 tail 使用独立投影；完成一个 turn 后再原子并入最新历史页。
- Activity 展开项、Diff 行和代码块都拆成同一个父级 `LazyColumn` 的稳定 entry。

这样 Markdown 解析完成、工具状态更新或新 token 到达时，只改变相关消息或 tail，不扫描
全部已加载历史。

### 3. 索引化消息存储

- 页面内部维护 `itemId -> slot` 索引，实时 tail 维护稳定顺序和 ID map。
- item started/completed、patch 和工具事件按 ID 定位，不再 `indexOfFirst` 扫描全列表。
- 同一主线程帧内的非流式工具通知合并后提交一次快照。
- 流式 Agent 文本继续使用独立 `StateFlow` 和 64 ms coalescer，不在每个 token 时改历史页。

### 4. 可视区 Markdown 调度

- UI 上报当前可见 assistant message IDs，并预取上下各约一屏。
- 单并发解析器只处理可见区、预取区和刚完成的实时回复；离开窗口的等待任务立即取消。
- AST LRU 保持双上限，并按设备内存等级使用 8 / 12 / 24 MiB 档位。
- `Application.onTrimMemory` 清理离屏 AST、首屏预览和已淘汰页面投影；不清当前可见文档。
- 超长单条回复按顶层 Markdown block 渐进进入父列表，避免首次显示时集中组合所有块。

### 5. 后台会话资源预算

- 正在回复或等待交互的会话不受 LRU 影响。
- 空闲 held app-server 默认最多保留 2 个，超限关闭最久未访问项并依靠 thread resume 恢复。
- 进入系统低内存状态时关闭所有空闲 held session，并清理对应 MCP proxy 和 Skill snapshot。
- 分别记录 Android、app-server/PRoot 和 MCP proxy 的 PSS、CPU 时间及存活数量，不能只看主进程。

## 实施阶段

| 阶段 | 内容 | 主要风险 | 完成条件 |
| --- | --- | --- | --- |
| P0 | Macrobenchmark、固定数据集、Perfetto trace、应用 Baseline Profile | 基准噪声 | 同设备连续 3 轮可复现，报告保留原始值 |
| P1 | item 索引、分页级增量投影、可视区 Markdown 调度 | key/锚点错位 | 50/300/1000 turn 顺序与滚动测试通过 |
| P2 | 8 页/4 MiB 有界窗口、淘汰和 cursor 重取 | 历史缺页或重复 | 20 页往返后正文哈希一致且 PSS 平台化 |
| P3 | Activity/Diff 父级块虚拟化、空闲会话 LRU、trim memory | 交互状态丢失 | 审批/输入/流式会话不被回收，空闲资源按预算释放 |
| P4 | ARM64 真机回归、文档和 Release gate | OEM 差异 | 固定门槛通过，Secure 真机完成；Lab 仅自动化验证 |

每个阶段单独提交。进入下一阶段前必须完成单测、完整 Secure/Lab JVM 测试、Lint、R8、
真机基准和代码审查；发现回归时修复当前阶段，不在发布构建中保留两套运行架构。

## 测试矩阵

### 正确性

- 50、300、1000 turn，重复 ID、迟到页、迟到失败、断线和 resume。
- 页首/页尾恰好落在 Activity、Markdown block、Diff 和流式 tail 中。
- 淘汰前后对全部 item 执行 `id + kind + text + patches` 哈希比对。
- 加载旧页时实时收到 item completed，验证两者不覆盖且无重复。
- 从旧历史快速回到底部，验证稳定 key、可见 offset 和自动跟随。
- 进程回收后从 app-server 重建，验证 Android 不依赖被淘汰的内存页。

### 性能

- 冷启动到会话列表、打开最近会话、首次排版、快速上下滚动和回到底部。
- 100 KiB 单回复、Markdown 表格、长代码块、100 个工具事件和持续流式回复。
- 每轮先重置 `gfxinfo`，记录 FrameTiming、P50/P90/P95/P99、Missed Vsync、PSS/RSS、
  Java/Native/Graphics heap、GC 次数、CPU 时间和 Runtime 子进程。
- 每个场景至少 3 轮，报告中使用中位数并保留全部原始样本。

共享 GitHub runner 只执行正确性、构建和基准工程可运行性，不把其模拟器帧时序作为性能
门禁。正式帧率和内存门槛在固定 ARM64 真机或受控设备农场执行。

## 数据完整性与安全

- 窗口淘汰只释放 Android 内存对象，不删除或改写 Codex rollout。
- page merge 必须以稳定 item ID 去重，正文冲突以实时/权威 completed item 为准。
- Markdown AST、页面投影和 cursor 仅存内存，不新增包含对话内容的磁盘缓存或备份数据。
- trim、退出和会话关闭时清除缓存引用；日志只记录页数、字节数和耗时，不记录正文、工具
  参数、附件路径、Authorization 或 MCP capability。
- 性能测试使用合成对话和测试凭据，不使用用户唯一的真实会话数据。

## 发布门禁

- `./scripts/verify-release.sh` 与 `./scripts/verify-stability-matrix.sh` 全部通过。
- Secure/Lab JVM、Lint、R8 和 ABI/通道隔离检查通过。
- 固定 ARM64 真机完成三轮基准，结果写回 `docs/CHAT_PERFORMANCE.md`。
- 对 20 页往返后的完整 item 哈希进行机器校验。
- 远端 Android CI 绿色后才能创建对应版本 Release；预发布失败不得以本地成功替代。
