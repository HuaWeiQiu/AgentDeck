# ADR-0013：受管扩展（Skills / MCP）双通道

- 状态：Accepted
- 日期：2026-08-11
- 相关：ADR-0010、ADR-0011、ADR-0012

## 背景

AgentDeck 需要在手机上按对话启用 Codex Skill 与 MCP，同时保留 Secure / Lab 两个产品通道。扩展不能直接拼进 `agent_cards`，也不能在共享 Codex Home 中按当前页面增删文件，否则并发会话会串用能力，凭据也可能进入 TOML、环境变量或进程参数。

Codex 0.147 已提供 MCP 配置、`default_tools_approval_mode`、`mcpServerStatus/list` 和 Skill 接口。AgentDeck 复用上游协议，不实现另一套 Agent 循环。

## 决策

| 能力 | Secure | Lab |
|---|---:|---:|
| 导入受控 `SKILL.md` | 是 | 是 |
| HTTPS Streamable HTTP MCP | 是 | 是 |
| None / Bearer 认证 | 是 | 是 |
| 每对话选择、工具 allowlist | 是 | 是 |
| 本地 stdio MCP | 否 | 是 |
| 原始 TOML MCP | 否 | 是 |
| Hooks / 插件 UI / 任意宿主进程 | 否 | 暂不提供 |

Gradle 生成独立的 `EXTENSION_LAB` 与 `EXTENSION_MAX_LEVEL`。Secure 上限为 L2（远程写工具），Lab 上限为 L4。本地 MCP 的 `command/args` 注入器只存在于 `src/lab`，Secure 使用拒绝实现；权限策略由数据层和启动层共同执行，不能只依赖界面隐藏。

## 数据与选择

Room 7→8 新增规范化表：

- `extensions`：名称、类型、状态、总开关和推导出的等级；
- `mcp_extension_configs`：连接参数与凭据引用，不保存明文；
- `skill_extension_configs`：规范包路径与 SHA-256；
- `extension_tools`：发现的工具、读写等级和 allowlist 开关；
- `agent_card_extensions`：对话与扩展的多对多选择。

保存对话与扩展选择使用同一 Room 事务。删除对话或扩展由外键级联清理选择。扩展变更只影响下一次连接；活动会话必须先释放后重连。

## Secure MCP

1. 远程地址必须是无 userinfo/fragment 的公共 HTTPS URL；DNS 解析拒绝回环、私网、链路本地、组播、文档网段与元数据地址，并关闭重定向。
2. Bearer 由独立 Android Keystore alias 加密，Room 只保存随机 `credentialRef`。Codex 只连接带 256-bit capability 的 Android 回环代理，Token 不进入 Codex 配置、环境变量、argv 或日志。
3. 代理固定单一上游，限制并发、Header、请求/响应大小和超时，不是通用转发代理。
4. 每个 MCP 使用会话随机化的受管 server ID，配置 `enabled_tools` 和 `default_tools_approval_mode = "prompt"`。远端的 `readOnlyHint` 只用于界面分级，不能免除上游 Codex 审批。
5. 启动前调用 `config/read` 获取有效 MCP 名称。Secure 为所有非受管名称注入 `enabled = false`；读取失败则停止连接。`agentdeck.config.toml` 在 Secure 中也拒绝活动的 `[mcp_servers.*]`。

## Skill 隔离

导入器只接受有严格 UTF-8、YAML frontmatter、受限字段、正文与大小上限的 `SKILL.md`。规范副本位于 App `noBackupFilesDir`，启动前再次校验 SHA-256。

每次连接只把该对话选中的 Skill 物化到不参与 `/run/agentdeck` 通用绑定的 `extensions/sessions/skills.<instanceKey>`。PRoot 只把当前会话快照覆盖绑定到进程内的 `/root/.agents/skills` 与 `/root/.codex/skills`，因此一个 app-server 看不到其他会话快照，也不会修改共享 Skill 目录。会话停止后删除快照；Canonical 包不交给 Codex 写入。

## 生命周期

`ExtensionSessionHandle` 同时持有 Skill 快照和 MCP 代理。所有权顺序为：

`ChatViewModel → ChatSessionRegistry（后台）→ teardown`

启动失败、初始化失败、取消、主动断开、后台空闲回收和恢复失败都会关闭 Handle。登录专用 app-server 不加载扩展。

## 界面

设置首页只保留一个“扩展”入口。扩展列表与详情按需渲染；新增时先选择 Skill、远程 MCP，Lab 才显示本地 MCP。对话编辑器只显示“扩展 · 已选 N 个”，独立选择器完成多选；聊天顶栏不再增加重复入口。

## 验证

- JVM：策略、Secure/Lab 配置合成、凭据 AAD、MCP JSON/SSE 与回环代理、启动参数。
- Room instrumentation：7→8、索引、坏引用与级联。
- 构建：Secure/Lab 双 ABI；Secure 产物不得包含 Lab 本地 MCP 注入器。
- 最终仅对 Secure 做 ARM64 真机安装、启动、Skill 会话隔离、远程 MCP、后台恢复及凭据泄漏检查。Lab 真机验收不在本阶段范围。

## 上游依据

- [Codex config reference](https://learn.chatgpt.com/docs/config-file/config-reference)
- [Codex App Server](https://learn.chatgpt.com/docs/app-server)
- [Build skills](https://learn.chatgpt.com/docs/build-skills)
