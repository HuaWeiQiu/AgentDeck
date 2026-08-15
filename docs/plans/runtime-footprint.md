# 计划：Runtime 与安装包体积拆分

- 状态：下一轮主线，待实施
- 适用版本：`0.2.0-beta.11` 起
- 动机：现在一份 Ubuntu + Codex rootfs 就要约 116 MB 下载、安装后近 1.1 GB。后面若再塞 DeepSeek Harness、pi、Claude Code，不能继续「一次下全套」。

## 结论

日常 Secure 只准备 **当前要用的那一条 CLI**。默认仍是 Codex。其它运行时按需下载，互不混装，卸载某一条不影响会话元数据和密钥。

APK 本身继续只带 PRoot/loader/talloc 和配方清单，不把 rootfs 打进包。

## 非目标

- 不把 Claude / DeepSeek / pi 的实现做完才开始拆体积。
- 不把多套 rootfs 预置进 APK。
- 不把 Lab 无障碍或语音模型打进 Secure。
- 不靠「压缩同一份 116 MB」假装解决多 CLI 问题。

## 用户看到的完成态

1. 第一次安装：只问「先准备 Codex」，下载还是现在那一份 Codex 组件，不再暗示还有别的大包。
2. 设置里「运行环境」按 CLI 列出：Codex 已就绪 / DeepSeek 未下载 / Claude 未下载。
3. 点「准备 DeepSeek」才开始下 DeepSeek 那一份；失败不影响已装好的 Codex。
4. 可以单独删除某一个运行时，会话名和人设还在。
5. 标准模式不出现镜像域名、PATH、exit code。

## 技术切法

| 层 | 做法 |
| --- | --- |
| 配方 | 每个 CLI 一份 recipe：自己的制品、校验、磁盘下限、apt 依赖 |
| 存储 | `runtime/<cli-id>/` 分目录，禁止共用一个 rootfs 当所有 CLI 的家 |
| 下载 | 复用现有续传、看门狗、国内/国际换源；进度按「正在准备 Codex」这种人话 |
| 启动 | `AgentRuntime` 按会话绑定的 recipeId 选目录，找不到就引导去准备，不偷偷下别的 |
| APK | 继续 ABI 拆分；清掉主路径用不到的资源（未接线的商店图、重复文档、调试夹具不进 release） |
| 语音 | Vosk 模型保持按需，不进默认首次下载 |

## 阶段

### V1. 量清楚再砍

- 列出 APK、首次下载、安装后磁盘的构成（PRoot、rootfs、Codex、apt 缓存、语音、调试资源）。
- 标出「默认必须有」和「可按需」。

### V2. Codex 单独成套

- 现有安装路径改名为明确的 Codex runtime。
- 设置页按 CLI 显示状态和占用。
- 删除 Codex runtime 不删 Room 会话/人设/备份。

### V3. 第二个 CLI 的空位

- recipe + 目录约定先为 DeepSeek Harness / pi / Claude Code 留好，但不预下载。
- 未实现的 CLI 显示「即将支持」，点下去不占磁盘。

### V4. 真接入时按需下

- 每接一条 CLI，只增加那一条的制品和自检。
- 两条 CLI 不得互相覆盖 `PATH` 或 home。

## 验收

- 冷安装 Secure：用户只下一份 Codex，没有第二份隐藏大包。
- 设置能看出 Codex 占用，并能单独删除。
- Secure APK 不含 Lab 无障碍；不含未使用的大资源。
- 现有会话备份/人设在删除 runtime 后仍在。

## 明确往后放

具体接 DeepSeek Harness、pi、Claude Code 的协议与认证，各自单独立项。本计划只保证它们到来时不必再下一整份「全家桶」。
