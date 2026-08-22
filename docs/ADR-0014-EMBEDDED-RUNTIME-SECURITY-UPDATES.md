# ADR-0014：内嵌 Runtime 安全更新策略

- 状态：Accepted
- 日期：2026-08-22
- 相关：ADR-0009、ADR-0013、ADR-0015

## 背景

内嵌 Runtime 当前使用 Ubuntu Base 24.04.4 rootfs，安装时一次性下载、校验 SHA-256 并整体替换，约 122MB 全量重下。该流程保证了 rootfs 完整性与可回滚性，但没有 CVE 跟进通道：rootfs 内的 OpenSSL、ca-certificates、zlib 等基础库只能等待下一次整体替换才能更新，安全补丁的时效性不可接受。

同时，Runtime manifest 对 rootfs、PRoot、Codex 采用统一的全量替换模型，任何组件变化都会触发 rootfs 重下，对移动网络用户不友好。

## 决策

第一版采用"rootfs 内增量"更新策略：

1. 安全补丁通过 rootfs 内 `apt update && apt upgrade` 打入。该动作由 App 在安装流程与 Doctor 修复流程中显式触发，不做后台自动更新；用户可感知、可跳过、可重试。
2. Codex CLI 二进制单独走 manifest 校验替换：manifest 中 Codex 条目独立版本化，版本或 SHA-256 变化时只下载并原子替换 Codex 归档，不重下 rootfs。此模型沿用 ADR-0009 已有的"Base rootfs、Codex 独立版本化"结论。
3. rootfs 大版本升级（24.04 → 26.04）不适用增量路径，仍走全量下载、校验、staging 解包与原子切换；增量补丁只允许同版本内的安全与错误修复。
4. 补丁失败不阻塞启动：`apt` 网络失败、dpkg 冲突或校验失败时，Runtime 仍以现有 rootfs 启动，仅在 Doctor 与设置页提示"安全补丁未完成，建议重试"。已打补丁的状态持久化到安装 marker，进程重建后可恢复展示。
5. 增量补丁在受控 PRoot 环境内执行，只操作 rootfs 内包管理器；不得执行 manifest 之外的远程脚本，不得触碰 `$CODEX_HOME` 与项目目录。

## 边界

- 增量补丁依赖 Ubuntu 归档源可用性；离线或弱网时补丁可延迟，不视为安装失败。
- 补丁后不重跑完整功能门禁，但 Doctor 保留 shell、DNS、TLS 与 Codex 探测，补丁后触发一次轻量验证。
- delta rootfs / zstd 差分包是后续演进备选：若增量补丁的体积、耗时或失败率不满足要求，可评估在 manifest 中引入按版本区间的差分 rootfs。本 ADR 不承诺该路径。

## 后果

- 安全补丁时效不再绑定 rootfs 整体替换，移动端下载量显著降低。
- 引入 rootfs "基础版本 + 补丁状态"两个维度，安装 marker 与 Doctor 报告需同时表达二者。
- `apt` 源可用性成为新的外部依赖；补丁失败必须始终是可重试的非阻塞事件。
