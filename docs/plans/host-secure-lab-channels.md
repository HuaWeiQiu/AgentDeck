# 计划：Secure / Lab 双通道

- ADR：0011（Secure L1）、0012（双通道）
- Secure：日常机测试  
- Lab：二手机；L2–L4 分阶段

## 已定

1. productFlavor `secure` / `lab`  
2. `HOST_LAB` + `HOST_MAX_LEVEL` 编译期门控  
3. 策略层拒绝超通道能力  
4. Lab source set 承载高权限组件  

## 阶段

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| C0 | ADR-0012 + 0011 修订 | 完成 |
| C1 | Gradle flavors + 策略门控 + 单测 | 完成 |
| C2 | Lab：L2 Intent 工具 + 设置入口 | 完成 |
| C3 | Lab：L3 无障碍 Service + snapshot/click | 完成 |
| C4 | Lab：L4 白名单 shell（非完整 Shizuku） | 完成 |
| C5 | 构建 secure/lab beta | 完成 |
| C6 | Lab 会话绑定修复 + guest CLI L2–L4 + 文案精简 | 完成 |

## 验收

- [x] `assembleSecureBeta` 包名无 `.lab`，无 a11y Service  
- [x] `assembleLabBeta` 包名含 `.lab`，关于页标明实验  
- [x] Secure 上 L2+ 通道 cap Denied  
- [x] 单测：max level / Lab flags 门控  
- [x] 仅开 Lab 能力（无 L1）时聊天仍 bind host session  
- [x] `agentdeck-host` 支持 intent/ui/priv 命令  

