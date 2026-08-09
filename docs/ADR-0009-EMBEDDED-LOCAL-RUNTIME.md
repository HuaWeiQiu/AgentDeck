# ADR-0009：内嵌本地 Runtime 与 Termux 兼容后端

- 状态：已接受
- 日期：2026-08-09

## 背景

0.1.x 通过独立 Termux App、`RUN_COMMAND`、proot-distro 和 Ubuntu 启动 Codex。该路线已经验证 Codex app-server、Provider broker 和原生聊天，但首次使用需要安装第二个 App、开启外部调用并处理 OEM 后台限制，无法达到普通客户的一步体验。

Termux 主应用的软件包路径、Bootstrap 和仓库与包名深度绑定，完整嵌入会引入自建软件仓库、较低 target SDK 和 GPL 组合合规风险。Local Desktop、UserLAnd 和 AndroidIDE 则证明了 APK 私有运行环境、版本化 rootfs 和宿主进程管理的可行模式，但它们的完整产品与许可证不适合直接复制。

## 决策

1. 引入稳定的 `AgentRuntime` 边界，聊天、安装、Doctor 和启动逻辑不得直接依赖 `TermuxGateway`。
2. 提供两个实现：
   - `EmbeddedProotRuntime`：目标默认后端。PRoot 与 loader 作为 APK native library 分架构打包；精简 ARM64 Linux rootfs 安装到 App 私有 `noBackupFilesDir`；官方 Linux Codex 在该 rootfs 内运行。
   - `TermuxRuntime`：0.1.x 兼容与迁移后端。在内嵌 Runtime 完成真机验收前继续可用，之后只在高级设置中出现。
3. 保持现代 Android `targetSdk`，不得为了执行下载文件降级到 28。宿主可执行代码必须来自 APK/native library；下载的 rootfs 只由受控宿主 Runtime 使用。
4. Runtime 包必须包含版本、架构、大小、SHA-256、签名和最低 App 版本。安装采用下载缓存、临时目录、校验、解压、功能探测和原子切换；失败保留上一可用版本。
5. 安装状态机至少覆盖：设备检查、空间检查、下载、校验、解压、安装工具、验证、就绪、需要操作、可重试失败和回滚。进程重建后从持久化事实恢复，不信任旧 UI 状态。
6. Base rootfs、Codex 和 AgentDeck wrapper 独立版本化，允许只更新 Codex。远程 Runtime manifest 必须签名；首个实现可以固定可信 URL，但不得执行未校验的远程脚本。
7. Runtime 进程由 AgentDeck 前台服务拥有，使用私有 socket 或一次性鉴权回环通道。离开页面、App 被杀和版本切换时按 instance lease 精确清理，不使用通配进程终止。
8. 现有 Codex app-server、Room conversation/thread 映射、Keystore vault、Provider broker 和聊天协议保持不变。Runtime 迁移不得改写或删除用户现有 Codex/项目数据。
9. PRoot、rootfs 内 GPL 工具和其他第三方二进制按各自许可证分发，提供对应 LICENSE、NOTICE、来源和源码获取方式。复制 GPL Java/Kotlin 实现前必须另行审查；优先独立执行经过校验的上游二进制并自行实现 Android 编排层。
10. 内嵌 Runtime 只有在当前 target SDK 的 ARM64 真机上完成 shell、DNS、TLS、git、Codex 登录、app-server、审批、锁屏恢复、更新和回滚验收后，才能成为稳定版标准默认值。预发布可以让全新测试安装默认进入内嵌 Runtime，以收集门禁证据，但必须保留 Termux 兼容回退并明确标记测试状态。

## 边界

```text
Android UI / Domain
        |
        v
AgentRuntime
  |-- EmbeddedProotRuntime
  |     |-- packaged PRoot + loader
  |     |-- verified private rootfs
  |     `-- official Linux Codex app-server
  |
  `-- TermuxRuntime
        `-- existing RUN_COMMAND + proot-distro path
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

1. 先抽象 `AgentRuntime`，以 `TermuxRuntime` 适配现有行为，确保零行为迁移。
2. 在独立实现中完成 ARM64 PRoot + 最小 rootfs 技术验证。
3. 加入版本化 installer、前台服务、Codex 和 app-server。
4. 真机通过后切换新安装默认值；已有 Termux 用户可继续使用或显式迁移。

## 后果

- AgentDeck 不再要求普通客户理解或安装 Termux。
- APK/首次下载体积、第三方许可证、后台进程和升级回滚成为新的主要风险。
- Termux 仍是已验证的兼容路径，不会在内嵌 Runtime 未成熟时被提前删除。
- 本决策替代 DESIGN 中“必须 Termux”和“免 Termux 属于 P2”的旧结论；ADR-0001 继续约束 `TermuxRuntime` 实现。

## 2026-08-09 实现进度

- 已完成领域级 `AgentRuntime`、`TermuxRuntime`、`EmbeddedProotRuntime` 和运行时路由，Doctor、安装、启动与聊天桥不再直接依赖 Termux。
- ARM64 APK 已固定打包 PRoot、loader 与 talloc，并锁定来源提交、许可证和 SHA-256。
- 内嵌安装器已固定 Ubuntu Base 24.04.4 与 Codex 0.147.0，执行限长下载、大小/SHA-256 校验、安全 staging 解包、基础工具安装、功能验证和原子启用。
- app-server 由精确 instance lease 和 Android `specialUse` 前台服务持有；Provider 参数保持 argv/TOML 结构，API Key 仍只经鉴权回环 broker 提供。
- 现有已完成设置的用户默认保留 Termux 兼容后端；完成 ARM64 真机门禁后才能把内嵌后端改为所有新安装的标准默认值。
- `0.2.0-beta.1` 已在 Android 16 / iQOO Neo8 ARM64 上通过首次准备、Provider 导入、真实 app-server 对话、IME/滚动、后台存活和精确进程树清理；新测试安装默认内嵌，稳定版门禁仍等待多 OEM、审批、异常恢复和回滚覆盖。
