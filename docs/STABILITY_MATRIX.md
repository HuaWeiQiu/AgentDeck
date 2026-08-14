# AgentDeck 稳定性矩阵

## 目标

稳定性结论必须来自可重复场景，而不是一次手工对话。矩阵分为 JVM 协议层、Runtime 文件/进程层和一次性 Android 设备层；任何层失败都会阻止预发布候选进入发布步骤。

## 场景分层

| 层级 | 必测场景 | 证据 |
| --- | --- | --- |
| RPC | 连接/请求超时、流中断、畸形/超大/突发消息、迟到响应、重复与乱序通知、审批断线 | JVM 测试 XML |
| Runtime | 无空间、断点续传、摘要错误、取消、staging 清理、精确进程回收、重复启停 | JVM/主机测试 XML 与脚本 JSON |
| Extensions | MCP JSON/SSE、断网/超时/重定向/SSRF/超限、capability、Keystore、Skill 完整性与会话隔离、资源释放 | JVM/Room 测试与 Secure 真机记录 |
| Android | API 26/target API x86_64 安装和会话恢复；ARM64 真机前后台、熄屏、低内存、进程重建、长流 | `device-matrix-*.json` |
| Chat performance (P0 compile) | Secure Macrobenchmark 工程可编译；50/300/1000 turn 合成数据 JVM 哈希 | Gradle compile + JVM XML |
| Chat performance (device) | 仅 Secure Beta，固定 ARM64 一次性设备；Lab 不作为官方帧率源 | `chat-performance/secure-*.json` |

## 安全约束

- 设备脚本只允许一次性模拟器或明确标记的测试设备。它不得自动对普通已连接手机执行卸载、清数据、断网、填满磁盘或重启。
- 默认矩阵只运行非破坏场景；破坏场景必须同时提供 `AGENTDECK_DISPOSABLE_DEVICE=1` 和显式序列号。
- 所有等待都有超时，所有临时文件和进程都有精确 owner/路径，失败后仍执行清理。
- 报告只记录版本、ABI、API、场景、耗时和结果，不记录聊天正文、凭据、路径中的用户名称或 capability token。

## 发布门禁

1. `verify-release.sh` 运行完整 JVM、R8、Lint、双 ABI 包内容和固定摘要检查。
2. `verify-stability-matrix.sh` 运行无设备依赖的协议/Runtime 场景并生成 JSON。
3. ARM64 真机执行非破坏回归；x86_64 在 API 26 与 target API 一次性模拟器执行安装、Runtime 探测、app-server 和 thread resume。
4. 长时 soak 至少连续完成 100 次请求/响应或 30 分钟，以先达到者为准；无未处理异常、ANR、孤儿进程和持续内存增长后才通过。
