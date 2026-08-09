# ADR-0007：受管模型服务与按需凭据

- 状态：已接受
- 日期：2026-08-09

## 背景

0.1.4 只复用 Codex 已有认证和 Provider。历史 `ProviderProfile` 保存名称、Base URL 与默认模型，但不参与 app-server 启动，因此用户无法在 AgentDeck 内添加 Sub2API 等 Responses 兼容服务，也无法根据 API Key 获取其可用模型。

直接把 API Key 写入 Room、`config.toml`、Termux 文件、Intent 或 argv 会恢复 0.1.0 已移除的泄露面。另一方面，Codex 0.147.0 的自定义 Provider 支持命令式 bearer token：`model_providers.<id>.auth.command` 在请求需要认证时执行固定程序，并从 stdout 读取 token。

## 决策

1. AgentDeck 同时支持两种认证所有权：
   - “当前 Codex 配置”继续完全由 Codex/CLI 管理，升级后仍是默认路径。
   - 用户主动添加的第三方 Provider 由 AgentDeck 管理 API Key。
2. 受管 API Key 使用 Android Keystore 的 AES-GCM 密钥加密，密文放入 `noBackupFilesDir`；Room 只保存不可反推密钥的 `credentialRef`。
3. 受管密钥不得进入 Intent、argv、shell 源码、`config.toml`、Termux 持久文件、日志、异常文本或备份。
4. 每个原生聊天实例在 Android 回环地址启动一个有界 credential broker。Termux 内的固定 token helper 读取本实例的 `0600` capability token，向 broker 请求对应 Provider 的密钥，并只把 bearer token 输出给 Codex。
5. Provider 配置通过当前 app-server 进程的 `-c` 覆盖传入。Base URL、Provider ID、模型和 broker 端口是非敏感结构化值；密钥只经过鉴权回环请求。
6. 受管 Provider 首批只支持 Codex 0.147.0 可用的 Responses wire API。Sub2API 与通用 OpenAI Responses 兼容服务使用 bearer token；Anthropic Messages 留给未来 Claude adapter。
7. 模型发现由 Provider adapter 负责。Sub2API/OpenAI 兼容 adapter 请求同源 HTTPS `/models`，解析 `data[].id`，限制响应大小和模型数量，并保留手动模型 ID 兜底。
8. 对话绑定 Provider 与模型。Provider 域名变化必须创建新配置；切换模型使用独立 thread 映射，不能让已有 thread 静默换上游或模型。
9. app-server 返回的实际 `modelProvider` 和 `model` 仍是最终事实。请求值与实际值不一致时，UI 必须失败关闭，不能显示假成功。

## 安全边界

- Android Keystore 只保护静态密文；应用进程、Codex 和受信任的 Termux 执行平面在使用时必然能接触明文。Root、被攻陷的 Termux UID 或被攻陷的 Android 进程不在本 ADR 的保护范围内。
- credential broker 只监听 `127.0.0.1`，限制单连接、消息长度、调用频率和实例生命周期；capability token 与 app-server token 分离。
- 删除 Provider 时先阻止新的 broker 请求，再删除 Keystore 密文和模型缓存。被对话引用的 Provider 不可直接删除。
- HTTP 客户端不记录认证头，不向跨域重定向转发密钥，只允许系统信任链验证通过的 HTTPS。

## 后果

- `ProviderProfile` 从历史展示数据升级为真实的运行配置，设置页重新提供“模型服务”入口。
- 原生聊天可以按对话使用多套 Sub2API/API Key，而不会替换用户现有 ChatGPT 登录或全局 `auth.json`。
- Termux TUI 继续使用 Codex 自身认证；0.1.5 的受管 Provider 只保证原生聊天链路，终端凭据共享需另行设计和验收。
- 配方新增固定 token helper，wrapper 与 Doctor 契约版本必须同步更新并接受真机泄露检查。
