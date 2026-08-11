# ADR-0011：Android 宿主工具桥（Host Toolkit）与能力分级

- 状态：**已接受（Accepted）**— 2026-08-11 用户确认开始执行；首期范围锁定 L1 + Host 公共管道
- 日期：2026-08-11
- 优先级约束：**安全 > 能力 > 体验 > 速度**
- 相关：ADR-0005（原生聊天桥）、ADR-0008（体验层级）、ADR-0009（内嵌 Runtime）

## 背景

AgentDeck 当前链路为：

```text
Compose UI → codex app-server (127.0.0.1 + capability token)
           → EmbeddedProotRuntime (App 私有 Ubuntu rootfs)
```

Codex 在 **PRoot 私有 Linux** 内可读写 `/root/projects`、执行 shell、走审批策略，这与桌面 Codex CLI「以用户身份操作整机」**不是同一权限边界**。

用户目标：在**显式授予高级权限**后，逐步让 Agent 能像 CLI 一样操作「真实手机工作区 / 部分宿主能力」，同时：

1. 不破坏「聊天优先、不自建 Agent 循环」；
2. 不默认 Root / 不默认无障碍全控；
3. 标准模式仍对普通用户安全、可理解；
4. **任何宿主能力失败必须默认拒绝，而不是静默扩大权限。**

## 决策

### 1. 宿主能力与 Runtime 严格分轨

| 轨道 | 职责 | 是否经 PRoot |
| --- | --- | --- |
| **Runtime 轨道（现有）** | Ubuntu、Codex、app-server、沙箱内文件与命令 | 是 |
| **Host 轨道（新增）** | 仅 Android 宿主可完成的能力（SAF 目录、Intent、无障碍 UI、可选特权 shell） | **否** |

- `AgentRuntime` / `EmbeddedProotRuntime` **不得**承担点屏幕、读其它 App、改系统设置。
- 新增 `AndroidHostToolkit`（领域接口）+ `HostToolBroker`（鉴权、策略、审批、审计）+ 可插拔 `HostCapabilityProvider`。
- Codex 仍拥有 Agent loop；宿主侧只执行 **白名单工具调用**。

### 2. 能力分级（默认全部关闭）

| 等级 | 名称 | 能力摘要 | 体验层级 | 默认 |
| --- | --- | --- | --- | --- |
| **L0** | Runtime-only | 现状：仅私有 rootfs / 现有审批 | 标准 | **开**（现状） |
| **L1** | 用户工作区 | SAF 授权目录的受限读写、列目录、搜索；不越权到未授权 URI | **高级** 显式开启 | **关** |
| **L2** | 协作 Intent | 打开指定 App、系统分享、选文件（用户可见系统 UI） | 高级 | **关** |
| **L3** | 屏幕代理 | 无障碍：读控件树 / 截图摘要、点击、输入、滚动 | **开发者** + 系统无障碍开关 | **关** |
| **L4** | 特权壳 | 可选 Shizuku/adb 白名单命令（装包、设置等） | 开发者 + 独立「我理解风险」开关 | **关** |

**本 ADR 批准实现范围：仅 L0 保持 + L1 落地 + Host 公共管道。**  
L2–L4 只定接口与安全门闩，**不在第一阶段实现执行器**。

### 3. 安全不变量（实现必须满足，测试必须覆盖）

1. **默认拒绝（fail closed）**  
   未开启对应等级、未持有有效 grant、会话不匹配、token 无效 → 返回结构化拒绝，不执行副作用。

2. **最小权限**  
   每个工具声明：所需 `HostCapability`、是否写操作、是否可离开用户可见 UI、最大 payload、超时。

3. **显式授权，可撤销**  
   - L1：系统 SAF 持久 URI + App 内「工作区绑定」列表；用户可随时解除。  
   - L3：系统无障碍开关 + App 内开关；任一关闭即失效。  
   - L4：Shizuku 授权 + App 内开关；缺一不可。  
   - 会话级 grant 可设 TTL；进程死亡后高危 grant 默认作废（L3/L4）。

4. **与 Codex 审批合流**  
   Host 写操作与危险读（L3 树、L4 shell）必须走与 ADR-0005 一致的客户可理解审批：`允许一次 / 拒绝`；标准模式 **禁止**「本会话始终允许」高危操作。高级模式若提供会话允许，须分能力记录且可一键清空。

5. **路径与参数硬校验**  
   - L1：所有路径解析后必须落在已授权 Document tree 内；拒绝 `..`、软链逃逸、content URI 伪造。  
   - 禁止把任意 `file://` 或绝对宿主路径交给 Codex 当「已授权」。  
   - 工具参数 schema 校验失败 → 拒绝。

6. **传输与鉴权**  
   - Host broker **优先进程内**调用；若必须用 socket，**只绑定** `127.0.0.1`，禁止 `0.0.0.0` / 未定义接口。  
   - 使用与 app-server 同等级的 **高熵短时 token**（会话 + instance 绑定，可 HMAC）；  
     **禁止**写入：Room 明文、聊天正文、Git、logcat 明文、**rootfs 世界可读路径**。  
     若必须落盘：仅 Android **宿主** App 私有目录（例如 `noBackupFilesDir/host-auth/`，**不得**放在 Ubuntu rootfs 内）、模式 `0600`、短 TTL、进程结束删除。  
   - 来自 Codex/rootfs 的调用必须证明「当前 app-server instance + conversation」绑定；  
     **禁止**长期把可重放的 Host 主密钥放进 Ubuntu rootfs。

7. **输出与日志脱敏**  
   - 审计日志：工具名、能力级、允许/拒绝、耗时、错误码；**不含**文件正文、控件全文、token、路径中的用户姓名可选用哈希。  
   - 返回给模型的内容：有字节/条目上限；L3 控件树截断；禁止自动附带通知栏、锁屏、密码框内容（见禁止列表）。

8. **禁止能力清单（任何等级都不得提供）**  
   - 读取或提交：密码框、支付 PIN、锁屏、通知中的 OTP/验证码（启发式拦截 + 控件类型过滤）。  
   - 静默发送短信、静默拨号、静默转账。  
   - 关闭自身审计、自我提权绕过审批。  
   - 未确认的跨用户/工作资料访问。  
   - 将 Host token 或 Keystore 材料写入 rootfs 或聊天记录。

9. **不因开发者模式绕过安全**  
   对齐 ADR-0008：开发者可看脱敏诊断，**不能**关闭路径校验、token 校验或高危审批。

10. **供应链与权限面**  
    - 第一阶段 **不新增** 无障碍 Service、Device Admin、QUERY_ALL_PACKAGES、REQUEST_INSTALL_PACKAGES。  
    - L1 仅使用 SAF / 已有存储访问模式；Manifest 变更必须单独安全评审。

### 4. 架构

```text
┌─ Compose（审批 UI / 高级设置：工作区与能力开关）─┐
│                                                  │
│  CodexRpcClient ◄──► codex app-server (PRoot)    │
│         │                     │                  │
│         │              (sandbox tools)           │
│         │                     │                  │
│         └──► HostToolBridge ──┤                  │
│                    │          │                  │
│                    ▼          ▼                  │
│            HostToolBroker（策略+审批+审计）       │
│                    │                             │
│         ┌──────────┼──────────┐                  │
│         ▼          ▼          ▼                  │
│      L1 SAF     L2 Intent  L3/L4 (stub)          │
└──────────────────────────────────────────────────┘
```

建议领域接口（示意，实现时可微调命名）：

```kotlin
enum class HostCapability { WORKSPACE_FS, SHARE_INTENT, UI_AUTOMATION, PRIVILEGED_SHELL }

data class HostToolCall(
    val conversationId: String,
    val instanceId: String,
    val tool: String,
    val args: Map<String, JsonElement>,
    val auth: HostAuthToken,
)

sealed class HostToolResult {
    data class Ok(val payload: JsonObject, val truncated: Boolean) : HostToolResult()
    data class Denied(val code: String, val userMessage: String) : HostToolResult()
    data class Error(val code: String, val userMessage: String) : HostToolResult()
}

interface HostToolBroker {
    suspend fun invoke(call: HostToolCall): HostToolResult
    fun listEnabledCapabilities(): Set<HostCapability>
}
```

### 5. L1 工具白名单（第一阶段唯一实现）

| 工具 | 写？ | 说明 |
| --- | --- | --- |
| `workspace.list` | 否 | 列目录，深度与条目上限 |
| `workspace.read` | 否 | 读文件，字节上限，文本/二进制策略 |
| `workspace.write` | 是 | 写/创建文件，大小上限，需审批 |
| `workspace.mkdir` | 是 | 创建目录，需审批 |
| `workspace.remove` | 是 | 删除（默认仅文件；目录删除需额外确认或禁止递归） |
| `workspace.stat` | 否 | 元数据 |

硬限制（建议默认，可配置但不可在标准模式关闭）：

- 单文件读/写：≤ 2 MiB（与附件策略同量级可再对齐）
- 列表单次：≤ 500 条目
- 递归深度：≤ 8
- 调用超时：读 15s / 写 30s
- 并发：每 conversation 串行或极低并行，防止交错写损坏

### 6. 与 Codex 的衔接方式（第一阶段选型）

**选定：进程内 Broker + 由 Android 在 app-server 会话侧注入「宿主工具适配层」**，优先顺序：

1. **优先**：若固定 Codex 版本支持 MCP/自定义 tool 注册，则在 **Android 宿主** 跑 MCP server（loopback + token），**不**把 MCP 放进不可信网络。  
2. **次选**：在现有审批/桥接层增加 `host_tool` 请求类型，由 Kotlin 执行后把结果作为 tool result 回灌 transcript（需协议适配调研）。  
3. **禁止**：让 PRoot 内进程直接持有 `AccessibilityService` 或任意 Binder 调宿主敏感 API。

第一阶段实现前必须在 `docs/plans/...` 中记录「与 Codex 0.147.x 实际对接方式」的调研结论；若协议不足，L1 可先做 **App 内「工作区面板」** 与 **受控 copy-in/copy-out** 降级：用户确认后，从已授权 SAF 复制到 `/root/projects/host-mirror/<grantId>/...`。  
镜像是 **沙箱内副本**，不是宿主挂载；UI 与 tool 结果必须标明 `mirror`；撤销 grant 时应提供清理镜像的动作。  
不得把镜像路径宣传为「已获得整机文件权限」，也不得跳过 Host 授权与审计。

### 7. 体验与文案

- 标准模式：不出现 Host 工具、不出现 Shizuku/无障碍营销入口。  
- 高级设置：`本机工作区（L1）` 说明「仅你选中的文件夹；可随时撤销」。  
- 审批文案示例：「Agent 请求写入工作区中的 `notes/todo.md`（约 1.2 KB）。不会访问你未授权的其它目录。」  
- 拒绝时客户可见原因；技术细节进开发者日志。

### 8. 非目标（第一阶段）

- L2/L3/L4 执行器  
- Root / Magisk 模块  
- 云端中继操控手机  
- 自动关闭 Play 保护或安装未知来源  
- 在标准模式默认打开任何 Host 能力  

### 9. 威胁模型（摘要）

| 威胁 | 缓解 |
| --- | --- |
| 模型被诱导读写未授权目录 | SAF 边界 + 路径规范化 + 默认拒绝 |
| 恶意 transcript / 重放 tool 调用 | instance+conversation 绑定 token；短 TTL |
| 本地其它 App 连 broker | 仅 loopback + token；优先进程内 |
| 日志泄露路径/正文 | 脱敏审计、有界 payload |
| 用户误开「完全访问」 | 分级默认关；高危不可会话一键全开（标准） |
| 供应链扩权 | L1 不扩危险 Manifest 权限 |

### 10. 验收门禁（文档阶段后的实现门槛）

实现合并前至少：

- [ ] JVM 单测：路径逃逸、未授权 URI、过期 token、错误 conversation、超大 payload、并发写串行化  
- [ ] 审批：写操作无 grant 时必弹；拒绝无副作用  
- [ ] 撤销 SAF 后所有 L1 调用立即 Denied  
- [ ] 开发者模式无法关闭校验  
- [ ] 无新增 L3/L4 权限进主路径 Manifest  
- [ ] `verify-release.sh` 相关单测通过  
- [ ] 真机：选目录 → 读/写/撤销 → 再写失败  

### 11. 后果

- 产品第一次有「可扩展宿主能力」的安全骨架，而不把手机变成默认可控设备。  
- L1 让「像 CLI 写项目文件」在用户授权目录上变得真实，同时保留 PRoot 作为默认隔离。  
- L3/L4 被显式推迟，避免在安全模型未稳时引入无障碍/Shizuku。  
- 与 ADR-0009 一致：Runtime 边界不被宿主能力污染。

### 12. 文档审阅记录（循环审阅 · 安全优先）

| 轮次 | 焦点 | 结论 |
| --- | --- | --- |
| R1 | 与 ADR-0005/0008/0009 边界是否冲突 | 分轨明确；Runtime 不承载宿主能力 — 通过 |
| R1 | 是否默认扩大 Manifest 攻击面 | L1 不新增危险权限 — 通过 |
| R1 | fail closed / 可撤销 / 禁止清单 | 已写；补强 token 不得进 rootfs — 已修 |
| R1 | copy-in 降级是否伪装全盘权限 | 已标明 mirror 语义与清理 — 已修 |
| R1 | L2–L4 是否会在实现时偷跑 | 计划与 ADR 双写「不在本阶段」— 通过 |
| R2 | 审批与「完全访问」Codex 档位叠加风险 | 明确 Host 审批 **独立于** Codex sandbox 档位，互不替代 — 见下节补丁 |
| R2 | 可执行性 | 阶段 P0–P6 可开工；P4 有协议风险与降级 — 通过 |

**R2 补丁（规范）：**  
即使用户将 Codex 权限设为「完全访问」，**也不得**自动启用 L1–L4。Host 能力仅由 Host 开关 + grant +（写操作）Host 审批共同决定。

### 13. 替代方案（否决）

| 方案 | 否决原因 |
| --- | --- |
| 在 PRoot 内直接操作 Android | 不可行且会伪造权限边界 |
| 默认开无障碍 Computer Use | 攻击面过大，违反安全优先 |
| 依赖用户已装的 Shizuku 作为唯一路径 | 绑定外部 App、权限过高、不适合默认产品 |
| 仅放宽 Codex「完全访问」 | 仍困在 App 私有 rootfs，解决不了宿主文件 |

## 状态流转

```text
Proposed（本文）
  → Accepted（用户确认 + 安全审阅勾选）
  → Implemented-L1（代码与门禁）
  →（未来）Amended for L2/L3/L4 各写增量 ADR 或修订节
```
