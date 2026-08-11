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
| C0 | ADR-0012 + 0011 修订 | 本文档同期 |
| C1 | Gradle flavors + 策略门控 + 单测 | 执行中 |
| C2 | Lab：L2 Intent 工具 + 设置入口 | 执行中 |
| C3 | Lab：L3 无障碍骨架（Service + 开关 + 工具 Denied/基础） | 执行中 |
| C4 | Lab：L4 Shizuku 骨架（无则 Denied 说明） | 执行中 |
| C5 | 构建 secure-beta 安装日常机 | 执行中 |

## 验收

- [ ] `assembleSecureBeta` 包名无 `.lab`，无 a11y Service  
- [ ] `assembleLabBeta` 包名含 `.lab`，关于页标明实验  
- [ ] Secure 上 `SHARE_INTENT` 等工具策略 Denied  
- [ ] 单测：max level 门控  
