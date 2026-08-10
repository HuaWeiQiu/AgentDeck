# ADR-0010：可编辑且持久化的 Codex 配置层

- 状态：已接受
- 日期：2026-08-10

## 背景

过去原生聊天只通过进程级 `-c` 参数注入受管 Provider，没有供用户保存和编辑通用 Codex 参数的入口；内嵌 `$CODEX_HOME` 又位于版本化 rootfs 中，Runtime 替换可能丢失配置。使用“当前 Codex 配置”的对话也只依赖本地 Provider 模型缓存，无法展示 Codex 自身的真实模型目录。

## 决策

1. AgentDeck 在 App 私有 `noBackupFilesDir` 中维护唯一源 `codex-home/agentdeck.config.toml`，高级设置提供完整 TOML 编辑、未保存返回确认、中文注释示例模板、官方参数参考和错误提示；旧的一行默认文件可无损升级为注释模板，真正自定义过的文件不自动改写。
2. Codex 0.147.0 的 `app-server` 明确不接受 `--profile`。原生聊天把已校验 TOML 转换为 JSON，并通过官方 `thread/start.config` / `thread/resume.config` 会话层注入；它叠加用户现有 `$CODEX_HOME/config.toml`，不得覆盖或删除全局配置。
3. 每次启动 app-server 前必须重新读取并校验 profile，生成该连接专用的不可变配置快照。当前产品只支持内嵌 Runtime，不再提供 Termux 运行方式、权限或同步入口。
4. 内嵌 Runtime 把独立 `codex-home` 绑定为 `/root/.codex`。首次启用时从旧 rootfs 迁移普通文件，不覆盖已存在文件、不跟随符号链接；后续 rootfs 更新不影响 Codex 数据。
5. Android 端使用结构化 TOML 解析器，限制配置为 128 KB，并拒绝 API Key、token、password、静态 HTTP headers 等明文凭据。第三方密钥继续只进入 Keystore 与实例级 credential broker。
6. 使用当前 Codex 配置时，Android 在 app-server 初始化后分页调用 `model/list`，以返回的 `model` 作为 turn override 值、`displayName` 作为界面标签。发现失败或 profile 使用自定义 Provider 时不阻断对话，至少保留 `thread/start` 返回的实际模型。
7. 模型和权限 override 属于当前聊天会话；后台持有和重新附着时必须恢复，受管 Provider 仍只允许选择该 Provider 已验证的模型集合。
8. 环境检测同时承认官方 Codex 登录和 AgentDeck 中已验证且密钥仍存在的模型服务。模型服务保存或删除后立即强制刷新。ChatGPT/API Key 登录由 Codex app-server 的账户协议处理，认证缓存与 `config.toml` 分离；登录不伪造或覆盖 AgentDeck 配置文件。

## 后果

- 用户可以在 App 内持久修改 Codex 的模型、推理强度、MCP、feature 等非敏感参数，不需要进入外部终端。
- 内嵌 Runtime 更新或 rootfs 替换后，Codex 认证、配置和 thread 数据仍然保留。
- 非法或含明文凭据的 profile 会在保存或启动前明确失败，旧的有效文件不会被半写入覆盖。
- AgentDeck 继续尊重用户全局 Codex 配置；受管 Provider 会从会话快照中移除 `model`、`model_provider` 和 `model_providers`，并以明确的 thread 参数锁定已验证的对话绑定，避免被 profile 静默改写。
- 已经在 App 内连接模型服务的用户不会再被错误引导到 Termux 或重复登录；密钥仍只在会话启动时通过 credential broker 注入。
