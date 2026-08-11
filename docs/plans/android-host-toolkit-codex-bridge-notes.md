# Codex ↔ Host Toolkit 衔接说明（P4）

## 选定方案

**Guest CLI + 文件 IPC（经 `/run/agentdeck` 绑定目录）**

| 组件 | 位置 |
| --- | --- |
| Guest CLI | `/usr/local/bin/agentdeck-host`（rootfs；会话 bind 时补装） |
| 会话文件 | `/run/agentdeck/host-session.json`（token+conversation+instance，0600） |
| 请求 | `/run/agentdeck/host-req/<id>.json` |
| 响应 | `/run/agentdeck/host-res/<id>.json` |
| Android | `HostToolRelay` 轮询 → `HostToolBroker` → SAF |

## 为何不用 PRoot 内直连 Host loopback MCP

PRoot guest 的 `127.0.0.1` 不是 Android 宿主 loopback，MCP HTTP 到宿主不可靠。  
绑定目录 `/run/agentdeck` ↔ `stateDir` 已存在，IPC 成本低且与现有 app-server token 文件模式一致。

## 安全要点

- token 仅短时会话文件，进程/解绑删除；不进 Room/聊天。
- 写操作走 `HostApprovalGateway` → 聊天「允许一次/拒绝」。
- Codex sandbox「完全访问」不自动开启 L1。
- 路径经 `HostPathGuard`；仅相对路径。

## 降级

若 Runtime 未装 `agentdeck-host`，bind 时从 assets 写入 active rootfs。  
完整重装 Runtime 时 `installRuntimeHelpers` 也会打包 CLI。
