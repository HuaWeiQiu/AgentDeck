# 计划：Lab 手机 UI Agent

- 状态：已确定方案，待实施
- 适用通道：仅 `lab`
- 建议版本：聊天性能第二阶段完成后的独立 Lab 预发布
- 参考实现：[Android Action Kernel](https://github.com/ethanjlimgit/android-action-kernel)
- 相关决策：[ADR-0011](../ADR-0011-ANDROID-HOST-TOOLKIT.md)、
  [ADR-0012](../ADR-0012-HOST-PRODUCT-CHANNELS.md)

## 结论

AgentDeck 可以在 Android 手机上实现 UI Agent，但不复制 Android Action Kernel 的 Python、
ADB 或第二套模型循环。Android Action Kernel 最值得吸收的是三件事：把无障碍树压缩为模型可用的
结构化界面、使用少量稳定动作、每次动作后重新观察。

AgentDeck 已经有 Codex app-server、Host Tool Broker、会话鉴权、审批和审计。最终链路为：

```text
Codex app-server（继续负责推理与 Agent loop）
    ↓ agentdeck-host ui.*
HostToolRelay（conversation + instance + 短时 token）
    ↓
DefaultHostToolBroker（通道上限、grant、审批、审计、限流）
    ↓
LabUiAutomationExecutor
    ↓
LabAccessibilityService（只在 Lab APK 中存在）
```

这套能力不需要云端中继，也不要求 Termux；它运行在 Android App 与内嵌 Runtime 之间。模型服务
仍可以是 ChatGPT 账号、API Key 或第三方 Provider，但宿主控制权始终由 Android Broker 决定。

## 产品边界

### 两个版本

| 项目 | Secure | Lab |
| --- | --- | --- |
| UI Agent 入口 | 不显示 | 开发者设置中显示 |
| 无障碍 Service | 不打包 | 可打包，默认关闭 |
| `UI_AUTOMATION` 能力 | 策略层拒绝 | 用户显式开启后可用 |
| 发布渠道 | 默认日常版本 | 仅 GitHub / 内部实验版本 |
| 高危审批与鉴权 | 必须保留 | 必须保留 |

Google Play 当前政策不允许使用 Accessibility API 的 App 自主发起、规划并执行行为或决策。
因此 UI Agent 不进入 Secure/Play 候选包。Lab 必须使用独立 applicationId、独立风险说明和独立
APK，不能靠一个隐藏开关在运行时切换。

Lab 是更高能力版本，不是无安全版本。以下硬约束在两个版本中都不能关闭：

- 永久禁止自动读取或输入密码、OTP/短信验证码、银行卡号、有效期、CVV、支付 PIN、助记词、
  私钥和生物认证内容。
- 进入登录验证、支付、转账、生物认证、锁屏、设备管理或系统授权页面时立即暂停，交给用户
  亲自完成；离开敏感页面后才允许重新开始。
- 禁止静默发短信、拨号、转账、安装/卸载、授予系统权限、启用无障碍或修改 AgentDeck 自身
  安全设置。
- Codex 的“完全访问”只影响 Runtime sandbox，不能替代 Host grant 或 Host 审批。

## 参考项目取舍

Android Action Kernel 的当前实现通过 ADB 执行 `uiautomator dump`，把 XML 清理成控件、文本、
边界和建议动作，再由模型循环选择点击、输入、滑动、返回等动作。AgentDeck 只吸收其信息压缩和
动作循环思想：

| 吸收 | 不照搬 |
| --- | --- |
| 过滤空容器，优先输出可交互和有语义节点 | 不依赖 USB、ADB 或 `uiautomator dump` 文件 |
| 输出控件角色、文本、bounds、状态和可用动作 | 不把坐标点击作为首选动作 |
| 一次动作后重新读取界面 | 不在 Android 侧再运行一套 LLM Agent loop |
| 少量、可验证、可限流的动作词汇 | 不把整个原始 XML、截图或敏感字段交给模型 |

实现采用 Kotlin 对 Android `AccessibilityNodeInfo` 做独立、可测试的 clean-room 转换。如果后续
直接移植参考仓库代码，必须保留 MIT 许可证与来源记录；当前方案不需要复制其源码。

## 核心模型

### UI 自动化会话

新增 `UiAutomationSessionManager`。同时最多存在一个活动 UI 会话，grant 至少包含：

```text
conversationId
instanceId
allowedPackages
expiresAt
remainingSteps
allowedActionClasses
sessionNonce
```

- grant 只在 App 进程内保存，进程死亡后失效，不进入 Room、Runtime 或备份。
- UI 会话必须由用户在 Lab 界面显式开始，Codex 不能自行打开无障碍设置或扩展 package scope。
- 默认时限 3 分钟、20 个动作；设置可在 5–50 步内调整，但每次任务开始都展示实际预算。
- 设备锁屏、屏幕关闭、无障碍被关闭、App/会话退出、超时或用户点击停止时立即撤销。
- 多个聊天同时请求时，后到者返回 `UI_SESSION_BUSY`，不能并行控制同一屏幕。

### 结构化快照

当前 `ui.snapshot` 的文本行只能用于原型。正式协议升级为版本化结构数据：

```json
{
  "schemaVersion": 2,
  "snapshotId": "ephemeral-id",
  "packageName": "com.example.app",
  "windowTitle": "...",
  "capturedAtEpochMs": 0,
  "truncated": false,
  "nodes": [
    {
      "nodeId": "ephemeral-node-ref",
      "parentId": null,
      "role": "button",
      "text": "提交",
      "resourceId": "submit",
      "bounds": [0.10, 0.82, 0.90, 0.91],
      "states": ["enabled", "clickable"],
      "actions": ["click"]
    }
  ]
}
```

快照规则：

- 只保留有文本、描述、资源 ID、可执行动作或为其提供必要语义的祖先节点。
- 默认最多 300 个节点、24 层、64 KiB；单字段最多 256 字符，超限明确标记 `truncated`。
- bounds 同时保留内部像素值与对模型输出的 0–1 归一化值，避免分辨率耦合。
- `nodeId` 使用会话随机盐、快照序号和节点指纹生成，只对当前 `snapshotId` 短时有效，不暴露
  原始 `AccessibilityNodeInfo` 或可跨会话重放的标识。
- 任何写动作、窗口变化或可访问性内容变化都会使旧快照失效。动作前重新获取节点并核对
  package、window、role、bounds 和文本摘要；不一致返回 `STALE_SNAPSHOT`。
- 不把原始快照写入 Room、日志、崩溃报告或聊天历史。工具时间线只保留 package、动作、结果码
  和脱敏标签。

### 敏感内容识别

在进入模型输出前统一运行 `SensitiveUiClassifier`：

1. 硬信号：`isPassword`、password inputType、信用卡/密码 autofill hints、生物认证/锁屏窗口。
2. 语义信号：OTP、验证码、CVV、PIN、银行卡、支付、转账、助记词、私钥等本地化词表。
3. App 范围：AgentDeck 自身、密码管理器、Authenticator、银行/支付类 App 默认拒绝。
4. 组合规则：页面含多个账户/支付信号时，整个窗口进入 `SENSITIVE_SCREEN`，不只隐藏单个节点。

分类器命中后不返回遮罩后的“可继续操作”快照，而是停止自动化并要求用户接管。启发式永远不能
被宣传为百分之百识别，因此 package allowlist、节点类型和用户确认必须共同生效。

## 工具协议

MVP 只提供节点级动作，不提供任意 shell、任意 Android Intent 或任意坐标：

| 工具 | 类型 | 约束 |
| --- | --- | --- |
| `ui.current_app` | 读 | 返回当前 package/window 与是否在授权范围 |
| `ui.snapshot` | 敏感读 | 返回结构化、有界、已脱敏的语义树 |
| `ui.click` | 写 | 必须提供 `snapshotId + nodeId`，一次快照只允许一次写 |
| `ui.scroll` | 写 | 优先节点的标准 scroll action；方向为固定枚举 |
| `ui.back` | 写 | Android 全局返回；离开 allowlist 前停下 |
| `ui.home` | 写 | 明确结束当前操作上下文，不自动恢复其它 App |
| `ui.set_text` | 写 | 仅普通非敏感文本框，使用 `ACTION_SET_TEXT`，禁止剪贴板中转 |
| `ui.wait_for` | 读 | 等待 package/window/节点条件，单次最长 5 秒 |

`HostToolCall.args` 当前是 `Map<String, String>`，无法可靠承载快照节点和类型约束。实施时将 Host
wire protocol 升级为 schema v2 和 JSON 对象；v1 工作区工具在迁移期保持兼容。所有参数由
Android 端 schema 校验，Codex 输出的 node ID、package 或坐标都不能直接信任。

`ui.set_text` 的普通文本不得放入命令行参数或审计日志。guest wrapper 通过标准输入接收最多
4 KiB 内容，写入 mode `0600` 的一次性请求后立即清除；Android 读取完成后先删除请求再执行。
敏感内容不是采用“加密后允许输入”，而是在进入这条传输链之前直接拒绝。

下列能力不进入 MVP：

- `dispatchGesture` 任意坐标点击/滑动；
- 截图上传与视觉坐标推理；
- 跨 App 自由跳转、通知栏读取、最近任务切换；
- 在 WebView/Canvas 中用 OCR 猜测按钮；
- Shizuku/Root 与 UI Agent 的组合动作。

如果原生节点覆盖不足，坐标手势只能作为后续独立阶段：Lab Manifest 单独增加
`canPerformGestures`，每次手势明确审批，并重新完成威胁模型和真机矩阵。截图能力同理，不能为了
“兼容更多 App”在 MVP 静默扩权。

## 审批与风险

| 动作等级 | 示例 | 默认处理 |
| --- | --- | --- |
| R0 观察 | current app、wait | 已开始的 UI 会话内允许，有界且审计 |
| R1 敏感观察 | snapshot | 任务开始时一次性告知范围；敏感页面直接暂停 |
| R2 可逆导航 | scroll、back、普通 tab | Beta 默认逐次审批；验证后可允许当前任务 |
| R3 内容改变 | click、set_text、开关 | 逐次显示目标 App、控件、内容摘要与影响 |
| R4 提交/外部影响 | 发送、发布、删除、购买、授权、安装 | 永久逐次审批，不提供“本次会话始终允许” |
| 禁止 | 密码/OTP/卡/PIN/生物认证、转账、安全设置 | 拒绝并暂停，用户接管 |

风险不能只按工具名判断。Broker 在动作前结合 package、节点角色、文本、祖先节点和邻近按钮推导
等级。例如普通 `ui.click` 点“展开”是 R2，点“删除账号”是 R4。无法确定时按更高等级处理。

审批页固定展示：目标 App、canonical 动作、控件文本/角色、是否提交外部影响、剩余步骤。远端
或模型生成的说明只能作为补充，不能覆盖这些 Android 本地可信字段。

## Agent 循环与失控防护

Codex 继续负责计划，Host 负责强制执行下列状态机：

```text
用户开始任务
  → snapshot
  → Codex 选择一个动作
  → Broker 校验 grant / snapshot / package / 风险
  → 必要时用户审批
  → 执行一个动作
  → 旧 snapshot 作废
  → 等待 UI 稳定并重新 snapshot
  → 完成、暂停或达到预算
```

硬限制：

- 一次只执行一个 mutation，不支持盲目动作链或模型提交多个 future actions。
- 同一动作与同一界面指纹连续两次无变化时暂停；同一快照哈希连续三次时返回
  `NO_PROGRESS`。
- 默认最多每秒 2 个动作，单动作 5 秒，任务 3 分钟；达到步骤、时间或错误预算立即停止。
- package 发生变化时，只有目标仍在本任务 allowlist 才继续，否则弹出跨 App 确认。
- 用户主动触摸或切换 App 时暂停自动执行。系统通知与 AgentDeck 内持久状态条均提供“停止”。
- AgentDeck 在控制目标 App 时通常处于后台，因此不能以 Activity 是否前台作为停机条件；Broker
  以目标 package、设备解锁状态、会话 lease 和用户停止信号判定。锁屏或进程回收直接结束，
  不自动恢复旧 grant。

## Package 范围

- 用户开始任务时选择允许操作的 App；默认只允许当前前台 App。
- 不申请 `QUERY_ALL_PACKAGES`。通过当前无障碍窗口、用户显式启动目标 App 和必要的精确 package
  query 形成候选列表。
- AgentDeck 自身 package 永久排除，防止 Agent 点击自己的审批、切换安全开关或扩大授权。
- 银行、支付、密码管理、Authenticator、系统设置、Package Installer、设备管理和锁屏组件进入
  默认拒绝集合；Lab 设置不提供关闭该集合的总开关。
- 从允许 App 跳到浏览器、第三方登录或支付页时自动暂停，而不是继承原 App 的 grant。

## 用户体验

入口放在 `设置 → 实验功能 → 屏幕 Agent`，不继续把所有配置铺在设置首页：

1. 首次进入展示能力、禁止事项和数据范围。
2. 用户点击“打开系统无障碍设置”，返回后 AgentDeck 再校验服务状态；App 不能自行启用。
3. 配置默认步骤数、任务时限和允许 App；高危硬约束不可编辑。
4. 聊天输入器在 Lab 增加手机操作入口。点击后先展示任务目标、App 范围、动作预算，再开始。
5. 运行时聊天顶部显示固定状态条，系统通知显示目标 App、已用步骤和停止按钮。
6. 工具时间线展示“观察 / 点击 / 输入 / 等待”的脱敏摘要，不把整个节点树当聊天消息渲染。
7. 每次 R3/R4 审批使用可滚动详情和固定操作区；敏感页面只显示“请在手机上亲自完成”。
8. 结束页显示完成、用户停止、超时、无进展、越界或敏感页面等明确原因。

## 性能与资源预算

无障碍树不能在主线程做递归清洗，也不能长期持有 `AccessibilityNodeInfo`：

- 事件使用 100–200 ms debounce 合并；遍历、过滤和 JSON 序列化在受限后台 dispatcher 完成。
- 单次快照目标：P50 ≤ 50 ms、P95 ≤ 150 ms；序列化结果 ≤ 64 KiB、节点 ≤ 300。
- Snapshot 只保留当前版本和动作核验所需的短时索引；窗口变化后立即回收 node references。
- 同时最多一个遍历任务；新窗口事件取消旧任务，不能在无界队列堆积 Accessibility events。
- UI Agent 空闲时不轮询、不截图、不持有 wake lock；`wait_for` 由有界事件等待实现。
- 100 步 soak 后 Java heap 不持续增长，Service 停止后节点索引、listener 和通知全部释放。
- 性能报告分别记录 AgentDeck PSS、内嵌 app-server PSS、snapshot 延迟、节点数、payload 和动作延迟。

这些是验收目标，不是尚未测量的现状承诺。实现前后要在同一 Lab ARM64 设备记录至少三轮。

## 代码落点

公共接口放 `src/main`，高权限实现只放 `src/lab`：

```text
src/main/.../domain/host/
  HostModels.kt                 # schema v2、工具与结果
  HostToolPolicy.kt             # 能力上限、风险与 deny reasons
  UiAutomationModels.kt         # 无 Android framework 依赖的数据模型

src/main/.../data/host/
  DefaultHostToolBroker.kt      # grant、审批、审计、串行化
  UiAutomationExecutor.kt       # interface + Secure no-op
  UiAutomationSessionManager.kt # 会话 lease 与预算

src/lab/.../data/host/lab/
  LabAccessibilityService.kt
  LabUiAutomationExecutor.kt
  AccessibilityTreeSanitizer.kt
  SensitiveUiClassifier.kt
  AccessibilityNodeResolver.kt
```

Codex 通过现有 `agentdeck-host` wrapper 调用，不新增可被局域网访问的服务，不把 Android Binder
或 AccessibilityService 暴露给 PRoot。Lab-only 内置 Skill 只描述观察/动作/再观察协议，不能
修改 Broker 策略；Secure APK 不包含该 Skill。

## 实施阶段

| 阶段 | 范围 | 完成条件 |
| --- | --- | --- |
| U0 | 协议 v2、threat model、测试 target App、release 扫描 | Secure/Lab 架构测试先红后绿 |
| U1 | `current_app`、snapshot、sanitizer、敏感分类；只读 | 合成树测试、64 KiB 上限、敏感页暂停 |
| U2 | node resolver、stale snapshot、click/scroll/back/home | 每次动作后失效；越界/陈旧节点无副作用 |
| U3 | set_text、wait_for、package grant、session manager、通知停止 | 普通输入闭环；所有敏感输入被硬拒绝 |
| U4 | Chat 启动入口、审批、脱敏时间线、Lab-only Skill | 用户可以开始、暂停、停止并理解当前范围 |
| U5 | Lab ARM64 真机兼容与性能矩阵 | 原生 View/Compose/WebView 基础场景通过 |
| U6 | 可选手势/截图单独决策 | 新 threat model、Manifest 审查和验收后才决定 |

每个阶段单独提交，执行“实现 → focused test → Secure/Lab 完整测试 → 审查 → 最小修复 →
复测”。U1–U4 之间不能用坐标点击临时掩盖节点协议缺口。

## 测试矩阵

### 单元与属性测试

- 原生 View、Compose、WebView 合成树；空容器、重复 resource ID、超深/超宽树和循环防护。
- password/inputType/autofillHint、多语言 OTP/银行卡/支付词表、混合敏感页面和 bidi/control 字符。
- node ID 跨 snapshot、跨窗口、跨 package、过期、篡改和重放全部失败。
- package allowlist、跨 App、AgentDeck 自身、系统组件和未知 package fail-closed。
- 步骤/时间/速率/no-progress 限制；并发任务只有一个 owner；取消后无迟到动作。
- schema v1/v2 兼容、超长文本、超大节点数、畸形 JSON 和日志脱敏。

### Instrumentation

建立专用 `ui-agent-test-target`，提供确定性的按钮、输入框、列表、弹窗、页面跳转和敏感字段。
自动测试只操作该 target，不使用用户的真实银行、聊天或账号 App：

- snapshot → click → snapshot；scroll → wait；set_text → verify。
- 内容在动作前变化时返回 stale，绝不点击新的同位置控件。
- 用户触摸、切后台、旋转、分屏、锁屏、Service 禁用和进程回收。
- 字体放大、中文/英文、Android 8–16 和主要 OEM 的无障碍树差异。
- 100 步 soak、重复启动/停止、错误事件风暴和低内存回收。

### 通道与发布门禁

- Secure Manifest 不含 AccessibilityService，dex 不含 Lab executor、sanitizer 或内置 UI Skill。
- Secure `HOST_MAX_LEVEL=1`，直接伪造 `ui.*` 调用必须返回 Denied。
- Lab 默认开关关闭；只有系统无障碍 + App 开关 + 当前 session grant 同时成立才执行。
- Release 脚本扫描所有新增 Lab-only 类和 service，而不只扫描一个旧类名。
- Lab 真机验收只用可清理的测试设备；本阶段不要求在用户主力 Secure 设备执行自动点击。

## 与当前性能工作的顺序

聊天性能第二阶段仍是 `beta.9` 当前优先事项。UI Agent 会新增 Service、工具事件和审批路径，先
完成已有聊天基准与有界历史窗口，才能准确测量 UI Agent 自身开销。推荐顺序：

```text
聊天性能 P0–P4
  → UI Agent U0–U4
  → Lab 专用真机 U5
  → 再决定坐标手势/截图 U6
```

若产品优先级调整，可以先做 U0/U1 的只读 snapshot 验证，但不应在聊天性能基准中途开放写动作。

## 验收定义

MVP 只有同时满足以下条件才算完成：

- Secure APK 不存在 UI Agent 可执行面，Lab APK 默认关闭并有明确风险说明。
- 用户可在一个授权 App 中通过节点完成观察、点击、滚动、普通文本输入和返回。
- 每个 mutation 都基于当前有效 snapshot，动作后强制重新观察，无盲目批处理。
- 密码、验证码、银行卡、支付、生物认证和系统授权场景全部暂停并交还用户。
- 跨 App、过期 grant、锁屏、进程回收、超时和无进展都能确定停止且不留后台动作。
- 审批、审计、Host token、conversation/instance 绑定和日志脱敏保持现有安全不变量。
- 自动化矩阵、Lab ARM64 真机三轮性能、100 步 soak 和 Secure 隔离扫描全部通过。

## 参考依据

- [Android Action Kernel](https://github.com/ethanjlimgit/android-action-kernel)：无障碍树压缩、动作词汇与
  observe/reason/act 参考；MIT。
- [Android AccessibilityService API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)：
  窗口树、全局动作、手势和截图的能力要求。
- [Android AccessibilityNodeInfo API](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo)：
  节点动作只能由 AccessibilityService 执行及节点生命周期约束。
- [Google Play Accessibility API policy](https://support.google.com/googleplay/android-developer/answer/16558241?hl=en)：
  自主规划/执行行为、披露、同意和用途限制；决定 UI Agent 仅进入 Lab 分发。
