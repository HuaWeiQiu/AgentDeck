# ADR-0004：CLI adapter 与用户数据完整性

- 状态：已接受
- 日期：2026-08-08

## 背景

v0.1.0 通过 `templateId` 的 `when` 分支生成启动命令，卡片只能修改工作区，Profile 与卡片之间没有数据库外键。删除 Profile 会留下悬空引用；编辑 Profile 会重置创建时间；删除最后一项后，应用重启还会重新写入示例数据。

## 决策

1. 每种 CLI 使用独立 `CliAdapter`，adapter 拥有 recipe/template 对应关系、Provider 类型、默认工作区、固定可执行入口、卡片校验和启动命令生成。
2. `CliAdapterRegistry` 拒绝重复 recipe/template，并在启动前拒绝配方与模板不匹配的卡片。
3. 卡片只有在配方 `available=true` 时才能新建或启动。旧版遗留的未开放 Claude 卡片保留供用户迁移或删除，但启动按钮禁用。
4. 卡片支持新建、编辑、启停和删除；保存与启动共用 adapter 规则。动态工作区和 CLI 参数始终作为 argv 传给固定 wrapper，不进入 shell 源码。
5. Room 数据库升级到 v3，`agent_cards.profileId` 引用 `provider_profiles.id`，删除 Profile 使用 `ON DELETE SET NULL`。迁移保留所有卡片，并把迁移前已悬空的引用置空。
   Profile 和卡片写入使用 Room `@Upsert`，禁止 `INSERT OR REPLACE` 触发外键的删除语义。
6. Profile 类型在仍被卡片引用时不可更改；删除前 UI 明确提示并报告解除绑定的卡片数量。编辑 Profile 保留原 `createdAtEpochMs`。
7. Base URL 只允许不含 user-info、query 或 fragment 的 HTTP(S) 地址，避免把凭据伪装在 URL 中写入数据库。
8. 示例数据只对全新数据库播种一次。v3 增加 `app_metadata.initial_seed_completed`；迁移来的旧库直接标记完成，用户删除的数据不会在下次启动时复活。

## 后果

- 增加新 CLI 时必须提供 adapter 与经过验证的 recipe，不能只添加一张展示卡片。
- Profile 删除不再破坏卡片行，卡片会明确显示未绑定状态。
- 数据库迁移必须继续保持外键、索引和初始化标记；发布验证需要覆盖 v1→v2→v3 与 v2→v3。
- Profile 目前仍是非敏感元数据。CLI 的 OAuth、API Key 和登录状态继续由 CLI 自己管理。
- Profile 绑定当前只提供类型约束和本地引用关系，不代表 AgentDeck 已改写 Codex `config.toml`；配置映射必须等官方配置契约稳定并由 adapter 单独实现。
