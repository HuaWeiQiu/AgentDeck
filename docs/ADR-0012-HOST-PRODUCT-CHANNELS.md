# ADR-0012：宿主能力双通道（Secure / Lab）

- 状态：**已接受（Accepted）** — 2026-08-11
- 日期：2026-08-11
- 优先级：**安全 > 能力**；Lab 不是「无安全」，而是「更高能力上限 + 知情同意」
- 相关：ADR-0011（Host Toolkit）、ADR-0008（体验层级）

## 背景

L1（SAF 工作区）已在安全默认路径落地。L2–L4（Intent / 无障碍 / Shizuku）攻击面显著更大。  
用户需要：

1. **安全版**：日常真机，只 L0–L1；  
2. **Lab 版**：二手机/实验，可启用 L2–L4；  
3. 两版**不要**共用一个默认 APK + 隐藏开关。

## 决策

### 1. 两个产品通道（编译期隔离）

| 通道 | Gradle flavor | applicationId 示例（beta） | 宿主上限 | Manifest |
| --- | --- | --- | --- | --- |
| **Secure** | `secure`（默认） | `com.agentdeck.app.debug` | **L1** | 无障碍 / Shizuku **不得**出现 |
| **Lab** | `lab` | `com.agentdeck.app.lab.debug` | **L4**（默认仍关） | 可含 a11y 等 Lab 专用组件 |

- `BuildConfig.HOST_LAB`：`secure=false`，`lab=true`  
- `BuildConfig.HOST_MAX_LEVEL`：`1` vs `4`  
- 策略层：任何 `HostCapability` 超过通道上限 → **Denied**（fail closed）

### 2. Secure 通道（日常）

- 仅 L0 Runtime + L1 SAF + Host 公共管道（ADR-0011 Implemented-L1）  
- 标准模式不展示 Host；高级设置仅工作区  
- 禁止：L2–L4 执行器、无障碍 Service、Shizuku 依赖  

### 3. Lab 通道（实验）

- 共享：Broker、token、审批、审计、L1  
- 另含：L2 Intent、L3 无障碍、L4 特权壳（分阶段实现，未完成的能力仍 Denied 并标明）  
- **仍必须**：  
  - 默认全部 Host 能力关闭  
  - 危险操作可审批（Lab 可提供「本会话允许」）  
  - 审计；token 不进 rootfs/聊天  
  - 禁止清单：静默短信/拨号/转账；密码框/OTP 采集回传模型  
- 安装/关于页必须标明：**高权限实验版，非默认 AgentDeck**

### 4. 代码布局

```text
src/main/     公共（含 L1 + broker）
src/secure/   可选：secure 专属文案
src/lab/      Lab 专属：a11y Service、L2–L4 provider、Manifest 合并
```

- Secure 编译不得依赖 `src/lab` 类型。  
- Lab 通过 `BuildConfig.HOST_LAB` 与 source set 接入，避免 secure R8 残留 Lab 入口。

### 5. 发布

| 产物 | 用途 |
| --- | --- |
| `app-secure-*-beta.apk` | 默认预发布 / 日常测试 |
| `app-lab-*-beta.apk` | 仅 GitHub 实验；文案含风险说明 |

版本号可共用 `versionName`，Lab 使用 `versionNameSuffix = "-lab"`（叠加 beta 规则以 Gradle 为准）。

### 6. 非目标

- 不在 Secure 包提供「一键变成 Lab」  
- Lab 不做 Root/Magisk 默认路径  
- 不因 Lab 关闭 Keystore / 路径校验 / Host token 校验  

## 后果

- 日常机与实验机物理分离（用户计划：安全版本机，Lab 二手机）。  
- Manifest 攻击面与能力上限在编译期绑定，降低误开与供应链混淆。  
- L2–L4 可按 Lab 通道迭代，不阻塞 Secure L1 稳定。

## 状态

```text
Accepted → 实现 flavor 门控 + Secure 安装验收
         → Lab L2/L3/L4 执行器分阶段（见 plans）
```
