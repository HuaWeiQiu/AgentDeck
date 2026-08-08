# ADR-0002：Termux 结果回传与 Doctor 状态

- 状态：已接受
- 日期：2026-08-08

## 背景

`startService()` 成功只说明 Android 接受了 Intent，不能证明 Termux 命令执行成功。v0.1.0 的环境检查用静态布尔值代替真实探测，安装入口也会在命令尚未结束时显示成功。

Termux `RUN_COMMAND` 支持通过 `PendingIntent` 返回后台命令的退出码、stdout、stderr 和内部错误。该回调需要可变 `PendingIntent` 才能由 Termux 填充结果。

## 决策

1. 前台 CLI 会话继续使用单向启动，不读取交互会话内容。
2. Doctor、安装和版本探测使用后台命令与结果回调，并设置有限超时。
3. 回调目标是 AgentDeck 内显式、不可导出的 `BroadcastReceiver`。
4. 每次命令使用一次性、可变的 `PendingIntent`；请求 ID 放在不可替换的 Intent data URI 中，不信任外部填充的关联 extra。
5. 回调只在内存中关联等待任务。应用进程被终止后结果可以丢失，UI 必须允许重新检测，不能长期停留在“检测中”。
6. stdout/stderr 只用于解析固定探测协议和展示短错误，不写入日志或数据库。Termux 标记输出被截断时，结果对象必须保留该状态。
7. Doctor 检查状态统一为：`未知`、`检测中`、`就绪`、`需要操作`、`被阻塞`、`错误`。
8. Codex 认证先使用官方的 [`codex login status`](https://learn.chatgpt.com/docs/developer-commands?surface=cli#codex-login) 探测，再检查登录 shell 中的 `OPENAI_API_KEY`、`CODEX_ACCESS_TOKEN` 和当前 Provider 声明的 `env_key` 是否非空。自定义 Provider 未声明 `env_key` 且未要求 OpenAI auth 时按 Codex 自身规则视为不需要登录。探针只返回固定状态，不输出凭据名称对应的值；Provider 要求的环境变量缺失时不能标记就绪。
9. 在 Codex 黄金路径的全部关键检查首次通过前，应用启动时默认进入 Doctor；用户仍可通过底部导航访问其它页面。

## 后果

- 环境状态来自真实命令结果，不再由“Termux 已安装”推断其它依赖已就绪。
- `allow-external-apps=false` 时后台命令可能无法返回，Doctor 以超时或内部错误提示用户在 Termux 中修复。
- 后续 Recipe 安装必须复用相同结果通道，安装按钮不能把 Intent 已发送显示为安装完成。
