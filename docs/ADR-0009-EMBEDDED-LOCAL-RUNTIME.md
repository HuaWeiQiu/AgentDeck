# ADR-0009：内嵌本地 Runtime

- 状态：已接受
- 日期：2026-08-09

## 背景

0.1.x 通过独立 Termux App、`RUN_COMMAND`、proot-distro 和 Ubuntu 启动 Codex。该路线已经验证 Codex app-server、Provider broker 和原生聊天，但首次使用需要安装第二个 App、开启外部调用并处理 OEM 后台限制，无法达到普通客户的一步体验。

Termux 主应用的软件包路径、Bootstrap 和仓库与包名深度绑定，完整嵌入会引入自建软件仓库、较低 target SDK 和 GPL 组合合规风险。Local Desktop、UserLAnd 和 AndroidIDE 则证明了 APK 私有运行环境、版本化 rootfs 和宿主进程管理的可行模式，但它们的完整产品与许可证不适合直接复制。

## 决策

1. 引入稳定的 `AgentRuntime` 边界，聊天、安装、Doctor 和启动逻辑不得依赖外部终端 App。
2. 产品只注入 `EmbeddedProotRuntime`：PRoot 与 loader 作为 APK native library 分架构打包；精简 ARM64 或 x86_64 Linux rootfs 安装到 App 私有 `noBackupFilesDir`；同架构的官方 Linux Codex 在该 rootfs 内运行。0.1.x 的 Termux 代码和数据库字段仅作为历史升级参考，不进入 Manifest、依赖注入、设置或启动流程。
3. 保持现代 Android `targetSdk`，不得为了执行下载文件降级到 28。宿主可执行代码必须来自 APK/native library；下载的 rootfs 只由受控宿主 Runtime 使用。
4. Runtime 包必须包含版本、架构、大小、SHA-256、签名和最低 App 版本。安装采用下载缓存、临时目录、校验、解压、功能探测和原子切换；失败保留上一可用版本。
5. 安装状态机至少覆盖：设备检查、空间检查、下载、校验、解压、安装工具、验证、就绪、需要操作、可重试失败和回滚。进程重建后从持久化事实恢复，不信任旧 UI 状态。
6. Base rootfs、Codex 和 AgentDeck wrapper 独立版本化，允许只更新 Codex。远程 Runtime manifest 必须签名；首个实现可以固定可信 URL，但不得执行未校验的远程脚本。
7. Runtime 进程由 AgentDeck 前台服务拥有，使用私有 socket 或一次性鉴权回环通道。离开页面、App 被杀和版本切换时按 instance lease 精确清理，不使用通配进程终止。
8. 现有 Codex app-server、Room conversation/thread 映射、Keystore vault、Provider broker 和聊天协议保持不变。Runtime 迁移不得改写或删除用户现有 Codex/项目数据。
9. PRoot、rootfs 内 GPL 工具和其他第三方二进制按各自许可证分发，提供对应 LICENSE、NOTICE、来源和源码获取方式。复制 GPL Java/Kotlin 实现前必须另行审查；优先独立执行经过校验的上游二进制并自行实现 Android 编排层。
10. 内嵌 Runtime 只有在 ARM64 真机与 x86_64 模拟器上完成各自发布门禁后，才能对对应架构宣称稳定支持；门禁包含 shell、DNS、TLS、git、Codex 登录、app-server、审批、会话恢复、更新和回滚。测试版不提供 Termux 回退；失败时保留独立的 Codex home、项目和配置并给出可重试修复动作。
11. Codex 用户数据和项目不得留在版本化 rootfs 内。`$CODEX_HOME` 与项目分别使用独立的 App 私有持久目录并绑定到 `/root/.codex`、`/root/projects`；首次升级只迁移普通文件且不跟随符号链接，rootfs 替换不删除认证、配置、thread 或项目数据。
12. Runtime 清单按 Android ABI 显式选择，目前只接受 `arm64-v8a` 和 `x86_64`。每个条目固定 PRoot/loader/talloc、Ubuntu Base、Codex 归档名、大小与 SHA-256；不允许跨 ABI 回退。GitHub 测试产物按 ABI 拆分，文件名必须带 ABI；Play/AAB 交给 Android ABI split。安装 marker 写入实际 ABI，架构变化或 marker 不匹配时重新验证 Runtime，不复用错误架构 rootfs。
13. `arm64-v8a` 与 `x86_64` 的宿主二进制必须来自同一固定 Kai/Termux PRoot/talloc 构建链。发布门禁同时检查两套 APK native entries、仓库哈希和 ELF machine；ARM64 用真机验收，x86_64 用 API 26 与当前 target API 的一次性模拟器验收。未完成某架构启动与会话恢复验收时，只能把该架构标记为候选，不能宣称稳定支持。
14. Runtime 升级先扫描所有带有效 marker 的旧版本 rootfs，把历史 `.codex` 与项目普通文件合并迁移到版本外持久目录；新 staging 完成自检并启用后，旧 rootfs 才能清理。清理使用不跟随符号链接的文件树遍历；准备、验证或提升失败时不得删除旧版本目录。

## 边界

```text
Android UI / Domain
        |
        v
AgentRuntime
  `-- EmbeddedProotRuntime
        |-- packaged PRoot + loader
        |-- verified private rootfs
        `-- official Linux Codex app-server
```

建议的最小接口：

```kotlin
interface AgentRuntime {
    val kind: RuntimeKind
    suspend fun inspect(): RuntimeReport
    suspend fun prepare(request: RuntimePrepareRequest): RuntimePrepareResult
    suspend fun start(request: AgentProcessRequest): AgentProcessHandle
    suspend fun stop(instanceId: String): Result<Unit>
}
```

接口使用领域请求和结果，不暴露 Intent、Termux 路径或 PRoot 参数。

## 分期

1. 抽象 `AgentRuntime` 并完成 ARM64 PRoot + 最小 rootfs 技术验证。
2. 加入版本化 installer、前台服务、Codex 和 app-server。
3. 将 Codex home 从版本化 rootfs 分离，保留旧应用数据并移除外部 Runtime 产品入口。
4. 通过真机门禁后再进入稳定发布。

## 后果

- AgentDeck 不再要求普通客户理解或安装 Termux。
- APK/首次下载体积、第三方许可证、后台进程和升级回滚成为新的主要风险。
- 0.1.x Termux 实现不再是可选产品路径；历史源文件和字段在后续独立迁移中清理，不得重新出现在用户界面。
- 本决策替代 DESIGN 中“必须 Termux”和“免 Termux 属于 P2”的旧结论；ADR-0001 仅记录旧版本边界。

## 2026-08-10 实现进度

- `ServiceLocator`、Doctor、安装、启动与聊天桥只接入 `EmbeddedProotRuntime`；Manifest 已移除 Termux 权限、查询和结果接收器。
- ARM64 APK 已固定打包 PRoot、loader 与 talloc，并锁定来源提交、许可证和 SHA-256。
- x86_64 使用同一固定构建来源，配套 Ubuntu amd64 与 Codex x86_64 归档；按 ABI 生成独立 APK，禁止把不匹配的 Runtime 发给设备。
- 内嵌安装器已固定 Ubuntu Base 24.04.4 与 Codex 0.147.0，执行限长下载、大小/SHA-256 校验、安全 staging 解包、基础工具安装、功能验证和原子启用。
- app-server 由精确 instance lease 和 Android `specialUse` 前台服务持有；Provider 参数保持 argv/TOML 结构，API Key 仍只经鉴权回环 broker 提供。
- Codex home 与 `/root/projects` 已从版本化 rootfs 分离并持久绑定；旧内嵌安装中的普通文件会在不覆盖目标文件的前提下迁移。AgentDeck 的独立 profile 在会话启动前校验和应用，详细边界见 ADR-0010。
- 设置和首次准备流程已移除 Runtime 选择及 Termux 专用动作；旧 Room 字段继续保留以确保非破坏升级。
- `0.2.0-beta.1` 已在 Android 16 / iQOO Neo8 ARM64 上通过首次准备、Provider 导入、真实 app-server 对话、IME/滚动、后台存活和精确进程树清理；新测试安装默认内嵌，稳定版门禁仍等待多 OEM、审批、异常恢复和回滚覆盖。
