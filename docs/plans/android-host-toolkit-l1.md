# 计划：Android Host Toolkit L1（安全优先）

- 对应 ADR：`docs/ADR-0011-ANDROID-HOST-TOOLKIT.md`
- 状态：**P0–P6 实现完成**（L1 + 文件 IPC + 写审批 UI）；后续仅维护与真机 soak
- 工作方式：按 `deliver-review-fix-loop` 每阶段 Implement → Test → Review → Fix → Retest → Rereview →（可选）本地 commit
- 总约束：安全 > 能力；默认拒绝；不实现 L2–L4 执行器

## 阶段总览

| 阶段 | 名称 | 产出 | 依赖 |
| --- | --- | --- | --- |
| P0 | 安全契约冻结 | ADR Accepted、威胁清单签字式自检 | — |
| P1 | 领域模型与策略 | 纯 Kotlin 类型 + Policy 单测 | P0 |
| P2 | L1 SAF 工作区存储 | URI 授权持久化、撤销、路径规范化 | P1 |
| P3 | HostToolBroker | 鉴权、限流、审计、审批钩子 | P1–P2 |
| P4 | Codex 衔接调研与最小对接 | 对接方式记录 + 可运行最小调用 | P3 |
| P5 | 高级设置 UI + 审批文案 | 用户可开/关/选目录/看状态 | P2–P4 |
| P6 | 真机门禁与发布说明 | 清单勾选、CHANGELOG 草稿 | P5 |
| — | L2/L3/L4 | **不在本计划** | 未来 ADR |

每阶段结束条件：该阶段测试通过 + 自审无新增高危问题 + 工作区干净（无无关改动混入）。

---

## P0 — 安全契约冻结

### 任务

1. 用户确认 ADR-0011 决策（尤其：仅 L1、默认关、禁止清单）。  
2. 确认 Codex 0.147.x 工具扩展可行路径的**调研任务**列入 P4（不阻塞 P1–P3）。  
3. 冻结「禁止清单」与「硬限制」数字（读/写 2MiB、列表 500、深度 8）。

### 验收

- [ ] ADR 状态可由 Proposed 改为 Accepted（人工）  
- [ ] 本计划无 L3 Manifest 权限预埋  

### 审阅焦点

- 是否有任何句子暗示「开发者可绕过审批」→ 必须删除  

---

## P1 — 领域模型与策略（无 Android 框架依赖）

### 任务

1. 新增包建议：`domain/host/`  
   - `HostCapability`, `HostToolCall`, `HostToolResult`, `HostAuthToken`  
   - `HostToolPolicy`：等级开关、工具→能力映射、写操作标记  
2. `HostPathGuard`（纯逻辑）：规范化相对路径、拒绝 `..`、拒绝绝对宿主路径、拒绝空段。  
3. 单测：权限矩阵、路径逃逸、未知工具、能力未启用。

### 验收

- [ ] 全部策略单测绿  
- [ ] 无 Manifest 变更  
- [ ] 默认 `listEnabledCapabilities()` 为空（除将来显式配置）  

### 审阅焦点

- 策略是否 fail closed  
- 错误是否可能变成 Ok  

---

## P2 — L1 SAF 工作区

### 任务

1. `WorkspaceGrantStore`：保存 tree URI、显示名、flags、创建时间；支持撤销。  
2. 使用 SAF `DocumentFile` / `ContentResolver` 实现 list/read/write/mkdir/stat；**remove 默认仅文件**。  
3. 所有操作先 `HostPathGuard` 再解析 document id。  
4. 单测：尽量用 Robolectric 或接口抽象 + 假 FS；逃逸与撤销后拒绝必测。

### 验收

- [ ] 撤销后 read/write → Denied  
- [ ] 越权相对路径 → Denied  
- [ ] 超大写入 → Denied（无部分写入或有原子性说明）  

### 审阅焦点

- 是否存在 content URI 拼接漏洞  
- 是否把 URI 字符串写进日志/Room 明文过多  

---

## P3 — HostToolBroker

### 任务

1. `HostToolBroker.invoke`：校验 token → 校验 conversation/instance → 校验能力 → 校验 schema → （写操作）审批 → 执行 → 审计。  
2. `HostApprovalGateway` 接口：对接现有聊天审批 UI（可先测试替身）。  
3. 审计：`HostAuditLog` 有界环形缓冲；开发者设置只读查看；脱敏。  
4. 限流：每 conversation 队列；全局超时。

### 验收

- [ ] 无 token / 错 conversation / 未审批写入 均 Denied 且无副作用  
- [ ] 审计无正文、无 token  
- [ ] 并发双写同一文件不损坏（串行）  

### 审阅焦点

- TOCTOU：审批通过后 grant 被撤销  
- 审批 UI 与 tool 超时谁取消谁  

---

## P4 — Codex 衔接（调研 + 最小对接）

### 任务

1. 阅读固定 Codex 0.147.x app-server/MCP 能力，写 `docs/plans/android-host-toolkit-codex-bridge-notes.md`：  
   - 可选方案 A/B 与选定理由  
   - 攻击面（rootfs 内进程如何拿到 loopback token）  
2. 实现最小对接：**一种**方式即可让「已授权会话」发出 `workspace.stat` 并得到结果。  
3. 若协议不足：实现 **受控 copy-in/copy-out** 降级（用户确认后复制进 `/root/projects/workspace-mirror/...`），并在 UI 标明「镜像副本，不是直接挂载」。

### 验收

- [ ] 桥接笔记落盘  
- [ ] 至少一条端到端：授权目录 → tool → 结果回聊天或测试 harness  
- [ ] rootfs **不能**在无 token 时调用成功  

### 审阅焦点

- token 是否落入 rootfs 文件 0600 以外的世界可读位置  
- 是否误把 Host 能力暴露给未绑定 conversation  

---

## P5 — UI（高级设置）

### 任务

1. 高级设置分组：`本机工作区`  
   - 开关（默认关）  
   - 选择文件夹、列表、撤销  
   - 简短风险说明  
2. 审批卡片文案与「拒绝/允许一次」。  
3. 标准模式扫描：无 Host 入口。

### 验收

- [ ] 标准模式无入口（UI 测试或导航测试）  
- [ ] 关闭总开关 = 全部 L1 Denied  
- [ ] 文案无「已 Root / 可控制整机」误导  

### 审阅焦点

- 是否诱导用户打开过高权限  
- 是否符合 ADR-0008 用语  

---

## P6 — 门禁与发布准备

### 任务

1. 真机脚本/清单：选目录、写文件、撤销、拒绝审批、杀进程后 grant 行为。  
2. `CHANGELOG` Unreleased 条目草稿。  
3. 若需升版：另开 release 流程（本计划不自动 push/release）。

### 验收

- [ ] ADR-0011 验收门禁清单全部勾选  
- [ ] `verify-release.sh` 中相关测试通过  

---

## 明确不做（防止范围漂移）

- 无障碍 Service、Shizuku 集成、自动点允许  
- `pm install`、读短信、通知监听  
- 在 PRoot 内挂载整机 `/sdcard`  
- 为方便测试在 debug 构建默认开启 L1  

## 风险与开放问题

| 风险 | 缓解 |
| --- | --- |
| Codex 0.147 难注册宿主工具 | P4 降级 copy-in/out（mirror 语义） |
| OEM SAF 行为差异 | 真机门禁；抽象 Document 访问 |
| 模型诱导删除工作区文件 | remove 默认非递归；写/删强审批 |
| 审计被当成间谍 | 仅本地有界、脱敏、用户可清空 |
| Codex「完全访问」被误当成 Host 全开 | ADR：两套开关独立，互不替代 |
| Host token 泄漏进 rootfs | 禁止长期主密钥进 Ubuntu；优先进程内 |

## 循环审阅检查表（每阶段结束必勾）

按 `deliver-review-fix-loop` 风险序：

1. **安全边界**  
   - [ ] 未授权 / 撤销 / 过期是否必 Denied  
   - [ ] 是否新增了本阶段不应有的 Manifest 权限  
   - [ ] 日志/Room 是否可能含 token 或文件正文  
2. **正确性**  
   - [ ] 写失败是否无半写入或有说明  
   - [ ] 审批拒绝是否零副作用  
3. **契约**  
   - [ ] 工具名与 ADR 白名单一致  
   - [ ] 错误码稳定、可测  
4. **测试证据**  
   - [ ] 单测命令与结果已实际运行（非假设）  
   - [ ] 无法运行的表面已写明  

文档阶段 R1/R2 已完成（见 ADR-0011 §12）。代码阶段从 P1 起每阶段重新跑本表。

## 建议执行顺序（开始写代码时）

```text
P0 确认 → P1 → P2 → P3 → P4 → P5 → P6
         每阶段跑 deliver-review-fix-loop
```

**当前停止线：** 文档 + 拆分 + 文档安全审阅完成；**不写代码，不 commit，不 push。**  
**下一步：** 你回复 **Accept ADR-0011**（可附带修改意见）→ 从 P1 开始实现。
