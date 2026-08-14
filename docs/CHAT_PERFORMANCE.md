# 聊天性能基线

## 实现约束

- 时间线只有一个父级 `LazyColumn`，不嵌套纵向懒列表。
- 已完成的 Agent 回复在后台解析为 Markdown AST，顶层语义块直接成为父列表项；段落、列表、代码块和链接的 Markdown 语义保持完整。
- 解析使用单并发调度器，流式回复完成前只渲染纯文本，避免逐 token 重复解析。
- AST 文档缓存采用访问顺序 LRU，上限为 24 条回复或 12 MiB；单条超大回复允许临时超过字节上限，确保当前内容可显示。
- 恢复会话时通过 Codex 0.147.0 `initialTurnsPage` 只读取最近 50 个 turn；滚动到顶部后使用 `thread/turns/list` 游标每次读取更早的 25 个。Android 内存不再先接收完整历史再截断。
- `ChatTranscriptRepository` 是已加载页、游标和实时尾部的唯一内存状态源；app-server rollout 是唯一持久化事实源，不用 Room 双写消息正文。
- 返回再进入时可先绘制最近一个会话的临时首屏预览，最多 120 个已完成 item 或约 256 KiB 字符；该上限不影响磁盘完整历史。预览没有游标且不落盘，首个 app-server 页面会整体替换它。
- 分页请求使用唯一 request token；重连前的迟到成功或失败不能覆盖新会话页，也不能释放新请求的单飞锁。
- transcript 状态与 composer 状态分离，输入框变化不会发布新的 transcript；用户开始拖动后立即停止自动跟随，流式滚动每帧最多执行一次。
- Markdown 颜色、排版、尺寸和组件环境由整条时间线共享，每个块只注入自己的引用链接处理器，不为每块创建独立状态流。

## 固定真机基准

测试日期：2026-08-10。设备：Vivo V2301A，Android 16，ARM64。构建：`0.2.0-beta.2-debug`。场景：打开同一个本地 `ds` 历史对话，等待内容排版完成，在 `x=630` 上依次执行三次 `(2050,800)` 向上滑动和三次 `(800,2050)` 向下滑动，每次 450 ms；滑动前重置 `dumpsys gfxinfo`，滑动后读取 `gfxinfo` 和 `meminfo`。

| 指标 | 优化前 | 优化后 | 变化 |
| --- | ---: | ---: | ---: |
| Total PSS | 215,042 KB | 209,047 KB | -5,995 KB (-2.79%) |
| Total RSS | 372,696 KB | 363,784 KB | -8,912 KB (-2.39%) |
| Java Heap PSS | 24,012 KB | 22,984 KB | -1,028 KB (-4.28%) |
| Native Heap PSS | 17,696 KB | 17,772 KB | +76 KB (+0.43%) |
| Graphics PSS | 74,724 KB | 73,868 KB | -856 KB (-1.15%) |
| PSS / 15,704,088 KB 设备内存 | 1.369% | 1.331% | -0.038 个百分点 |
| 卡顿帧 | 6 / 214 (2.80%) | 3 / 206 (1.46%) | -1.34 个百分点 |
| P50 / P90 / P95 / P99 | 9 / 16 / 17 / 46 ms | 7 / 15 / 17 / 28 ms | P99 -39.13% |
| Missed Vsync | 2 | 1 | -50% |

这组“优化后”数据对应阶段一 AST 时间线与状态隔离，但仍是未开启 R8 的 Debug 构建。[Android 官方 Lazy 列表文档](https://developer.android.com/develop/ui/compose/lists#measuring-performance)明确要求使用开启 R8 的 release 类构建可靠测量，因此它只保留为中间过程，不能作为最终发布性能。

## 最终 Beta 双样本

最终 `beta` 构建与 Debug 使用相同测试签名和 application ID，可以保留手机里的 Runtime、模型服务和对话；区别是 Beta 继承 release 的 R8/资源压缩并打包依赖 Baseline Profile。以下两组数据来自同一台手机、同一个 `ds` 对话和同一组 6 次手势，连续执行且每轮前单独重置 `gfxinfo`。

| 指标 | 优化前 | Beta 第 1 次 | Beta 第 2 次 | Beta 平均变化 |
| --- | ---: | ---: | ---: | ---: |
| Total PSS | 215,042 KB | 113,012 KB | 131,352 KB | -92,860 KB (-43.18%) |
| Total RSS | 372,696 KB | 254,000 KB | 276,744 KB | -107,324 KB (-28.80%) |
| Java Heap PSS | 24,012 KB | 6,844 KB | 19,744 KB | -10,718 KB (-44.64%) |
| Native Heap PSS | 17,696 KB | 6,224 KB | 7,880 KB | -10,644 KB (-60.15%) |
| Graphics PSS | 74,724 KB | 73,004 KB | 73,296 KB | -1,574 KB (-2.11%) |
| PSS / 15,704,088 KB 设备内存 | 1.369% | 0.720% | 0.837% | -0.591 个百分点 |
| 卡顿帧 | 6 / 214 (2.80%) | 17 / 1,234 (1.38%) | 1 / 321 (0.31%) | 两次均低于基线 |
| P50 / P90 / P95 / P99 | 9 / 16 / 17 / 46 ms | 7 / 12 / 19 / 23 ms | 6 / 7 / 8 / 13 ms | P99 下降 50.00%–71.74% |
| Missed Vsync | 2 | 3 | 0 | 第 2 次为 0 |

双样本平均 Total PSS 为 122,182 KB（设备内存占比 0.778%），平均 Total RSS 为 265,372 KB。单次 PSS/Java 堆会随 ART GC、已访问的历史页和可回收 Skia 缓存波动，所以同时保留两次原始值，不用最低值代表最终收益。

这些结果是同一台真机上的测试签名 Beta 构建对比，不代表所有 Android 设备。稳定版发布仍需按 `RELEASE_CHECKLIST.md` 覆盖更多 OEM、超长单条回复、表格/代码块、输入法、持续流式回复和多页历史。

## 第二阶段 P0 工程

聊天性能继续以 Secure 为基座：Lab 只叠加 Host/MCP 实验能力，不另做一套时间线。

| 资产 | 用途 |
| --- | --- |
| `ChatPerformanceFixtures` | 固定 50 / 300 / 1000 turn 合成对话，分页与 Codex 0.147.0 一致 |
| `ChatTranscriptIntegrity` | `id + kind + text + patches` SHA-256，供窗口淘汰前后比对 |
| `ChatPerformanceBenchmarkActivity` | Secure flavor 隔离界面，只渲染生产 `ChatTranscript`；Lab 不打包 |
| `IndexedChatItems` + `TimelinePageProjection` | P1 索引与页级增量投影，避免整表扫描 |
| 有界历史窗口（8 页 / 4 MiB） | P2 页淘汰 + cursor 重取；只释放 Android 内存，rollout 不动 |
| `ChatMemoryTrim` + 空闲会话 LRU | P3 trim 分发、最多 2 个空闲 held session、活动虚拟化 |
| `:macrobenchmark` | Secure Beta Macrobenchmark 与 Baseline Profile 生成器 |
| `scripts/verify-chat-performance-compile.sh` | CI / 发布门禁：只编译，不连设备 |
| `scripts/verify-chat-performance.sh` | 一次性 Secure ARM64 真机采集；拒绝 Lab 包和日常机 |

## 第二阶段改造后真机数据

测试日期：2026-08-14。设备：Vivo V2301A，Android 16，ARM64（与第一阶段基线同机）。
构建：Secure Beta（含 P0–P3 全部改造与排版竞态修复）。方法：与第一阶段相同的
gfxinfo + 固定手势（6 次 450ms 滑动，滑动前 reset），场景改为
`ChatPerformanceBenchmarkActivity` 的合成 50 / 300 / 1000-turn 对话，每个场景约
320 帧。

| 指标 | 50 turn | 300 turn | 1000 turn | 阶段一基线（真实对话） |
| --- | ---: | ---: | ---: | ---: |
| Total PSS | 111,289 KB | 112,121 KB | 112,561 KB | 122,182 KB（两次均值） |
| Total RSS | 251,168 KB | 251,900 KB | 252,152 KB | 265,372 KB（两次均值） |
| 卡顿帧 | 5 / 319 (1.57%) | 3 / 318 (0.94%) | 4 / 320 (1.25%) | 1.38% / 0.31% |
| P50 / P90 / P95 / P99 | 6 / 9 / 11 / 21 ms | 6 / 9 / 10 / 16 ms | 6 / 9 / 11 / 16 ms | 7-9 / 12-16 / 8-19 / 13-23 ms |
| Missed Vsync | 1 | 0 | 1 | 3 / 0 |

核心结论：**历史长度不再是变量**。1000-turn 与 50-turn 的 Total PSS 差只有
1,272 KB（+1.1%），卡顿率与分位数在噪声范围内打平——有界窗口（8 页 / 4 MiB）
+ 页级增量投影生效。与阶段一基线对比时需注意：基线是真实 `ds` 对话、改造前
代码，本组是合成对话、改造后代码，场景不同但方法、设备、手势一致。

Macrobenchmark 路线在这台设备上不可用：Funtouch 丢 profileinstaller 广播（已改
`Partial(Disable, warmup=3)` 绕过），且每帧迭代间 Perfetto trace 处理要卡约
13 分钟，三轮场景需要数小时。gfxinfo 固定手势是与阶段一直接可比的替代方案。

### 真机适配记录（Vivo V2301A, Android 16）

- `CompilationMode.Partial()` 默认路径依赖 profileinstaller 安装广播；Funtouch 的
  后台广播限制会丢包，三个用例全部报 "baseline profile install broadcast was not
  received"。已改为 `Partial(baselineProfileMode = Disable, warmupIterations = 3)`：
  同一台设备同一模式前后可比，但不与支持 profileinstaller 的设备数字混比。
- `ChatPerformanceBenchmarkActivity` 的 `onMarkdownNeeded`/`onVisibleItems` 是空实现，
  除预热文档外的 assistant 消息会一直显示"正在排版回复"——这是基准界面的设计行为，
  不是聊天回归；它渲染固定合成场景供帧时序对比。
- 真机调试暴露并修复了一个真实竞态：块级可见性单条上报会替换视口级集合，导致
  相邻已排版 AST 被逐出、消息卡在排版态。可见集合现在只有视口 snapshotFlow 一个
  写入者，详见 CHANGELOG。
