# 排查：Codex CLI 接小红书 dots 报 HTTP 400

- 状态：基于**官方文档**的结论（2026-08-17）；未替用户持有其私有 API 密钥做实网打点
- 不猜测「神秘 400」；下列原因均可在官方材料中对上号

## 官方事实

### 1. Codex 自定义 Provider 使用 `wire_api`

OpenAI Codex 高级配置（[Custom model providers](https://developers.openai.com/codex/config-advanced)）定义 Provider 时包含 **base URL、wire API、认证**。示例使用：

```toml
model = "gpt-5.6-terra"
model_provider = "proxy"

[model_providers.proxy]
name = "OpenAI using LLM proxy"
base_url = "https://proxy.example.com/v1"
wire_api = "responses"

[model_providers.proxy.auth]
command = "/usr/local/bin/fetch-codex-token"
# ...
```

官方讨论 [Deprecating `chat/completions` support in Codex #7782](https://github.com/openai/codex/discussions/7782)：

- Codex 历史支持 chat 与 responses；**chat/completions 正在弃用**，完整移除目标为 **2026 年 2 月初**。
- 若配置了 `wire_api = "chat"` 或未正确指定，需改为 **`wire_api = "responses"`** 才能走官方推荐路径。
- 默认 OpenAI 托管模型已是 responses；**自定义 Provider 必须显式配置 wire_api**。

### 2. AgentDeck 受管 Provider **只发 responses**

本仓库固定注入（不可被用户 TOML 悄悄改成 chat）：

- `EmbeddedProotRuntime` / `wrappers/codex-app-server-start.sh`：  
  `model_providers.<id>.wire_api = "responses"`
- 导入现有 CLI Provider：`wire_api` 必须为 `responses`，否则拒绝（`ExistingCodexProviderImporter`）。
- ADR-0007：首批只支持 **Responses wire API**。

因此：在 **AgentDeck 原生聊天**里配任何只实现 Chat Completions 的上游，Codex 会打 **`POST …/v1/responses`**（或等价 responses 路径）。上游若只认 **`/v1/chat/completions`**，常见结果就是 **HTTP 400**（或 404/405，视网关实现而定）。

### 3. dots3-note 官方示例是 **Chat Completions**

[studio-dots-ai/dots3-note-prev README](https://github.com/studio-dots-ai/dots3-note-prev)（中英一致）快速开始：

```python
from openai import OpenAI

client = OpenAI(base_url="http://127.0.0.1:8000/v1", api_key="EMPTY")

response = client.chat.completions.create(
    model="dots3-note-prev",
    messages=[...],
    # enable_thinking 等在 extra_body
)
```

部署说明是 **SGLang / vLLM** 提供 OpenAI 兼容服务，示例路径与 SDK 均为 **chat.completions**，**没有**给出 Codex 所需的 **Responses API** 兼容说明。

产品站 [studio.dots.ai](https://studio.dots.ai/) 为前端站点；**开源权重 README 并未定义「给 Codex 用的官方 hosted Responses base_url」**。若你用的是第三方转发/中转，必须以该转发是否实现 **`/v1/responses`** 为准，不能假设「OpenAI 兼容 = Codex 能用」。

## 400 最可能的原因（按优先级）

| # | 原因 | 为何像 400 | 怎么验证（你本机） |
| --- | --- | --- | --- |
| A | **协议不一致**：Codex 发 responses，dots/网关只吃 chat/completions | 请求体/路径不被接受 | `curl -sS -o /tmp/r.json -w '%{http_code}' -X POST "$BASE/responses" ...` 与 `.../chat/completions` 对比 |
| B | **base_url 路径重复或缺失** `/v1` | 打到错误路由 | Codex `base_url` 应是 **API 根**（官方示例含 `/v1`）；不要写成 `.../v1/chat/completions` |
| C | **模型名不对** | 部分网关用 400 表示 unknown model | 与平台「模型 ID」逐字一致（开源示例为 `dots3-note-prev`） |
| D | **鉴权/Header** | 偶发 401/403，少数网关统一 400 | Key、是否要求额外 header |
| E | **多模态/thinking 字段** | chat 示例里的 `chat_template_kwargs` 不是 Codex responses 字段 | 用最小文本 turn，关掉花活再试 |

**不要**在未确认上游支持 responses 时，把 AgentDeck/`wire_api` 改回 `chat` 当长期方案：与 Codex 官方弃用方向和本项目 ADR-0007 冲突。

## 推荐处置

### 你若用的是「桌面 Codex CLI + 自建/中转 dots」

1. 先用 **curl 探针**（勿把 Key 贴进聊天/日志）：

```bash
# 1) 上游是否真有 Responses
curl -sS -D- -o /tmp/dots-responses.body \
  -X POST "${DOTS_BASE%/}/responses" \
  -H "Authorization: Bearer $DOTS_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"YOUR_MODEL_ID","input":"hi"}'

# 2) 上游 Chat Completions（官方 dots 示例形态）
curl -sS -D- -o /tmp/dots-chat.body \
  -X POST "${DOTS_BASE%/}/chat/completions" \
  -H "Authorization: Bearer $DOTS_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"YOUR_MODEL_ID","messages":[{"role":"user","content":"hi"}]}'
```

- **仅 2 成功、1 失败** → 不是 Codex「坏了」，是 **协议不匹配**。  
  选项：换支持 responses 的网关/中转；或 **不要用 Codex 连该上游**，改用支持 chat 的客户端（例如 dsh 自定义 `openai-completions`，见 [dsh providers 文档](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/user/guide/providers.zh.md)）。
- **两者都失败** → 先修 base_url / 模型 ID / Key，再谈 Codex。

2. 仅当 1 成功时，再写 Codex：

```toml
model = "YOUR_MODEL_ID"
model_provider = "dots"

[model_providers.dots]
name = "dots"
base_url = "https://YOUR_HOST/v1"   # 官方形态：.../v1
wire_api = "responses"
# env_key 或 auth.command 按你的密钥注入方式
```

### 你若用的是 AgentDeck 原生聊天

- 受管 **Responses** Provider **只会** `wire_api=responses`。
- **2026-08-17 更新**：AgentDeck 已增加一等公民 **Chat Completions** 模型服务
  （`OPENAI_CHAT_COMPLETIONS`），供 **pi / dsh** 使用；密钥仍走 vault。
  用 dots 时请新建 Chat Completions 配置并绑定 **pi**，不要绑 Codex 原生聊天。
  详见 `docs/HANDOFF.md`、`docs/plans/runtime-pi-dsh.md`。  
- 只提供 chat 的 dots **无法**作为合格受管上游，直到中间层补上 responses。  
- 产品侧正确方向见 `docs/plans/runtime-pi-dsh.md`：chat 类网关更适合 **dsh Web**，而不是硬拧进 Codex。

## 需要你补充的信息（若仍 400）

在**不发送密钥**的前提下：

1. 完整 **base_url**（可打码域名中间段）与 **model id**  
2. 是 **桌面 Codex CLI** 还是 **AgentDeck**  
3. `curl` 对 `/responses` 与 `/chat/completions` 的 **HTTP 状态码 + 响应 body 前 500 字（无密钥）**  
4. Codex 版本（AgentDeck 钉死 **0.147.0**）

有这四项可以把原因从「A/B/C」收敛到一条。
