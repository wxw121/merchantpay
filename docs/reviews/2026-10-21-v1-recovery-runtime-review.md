# V1 恢复、运行补丁及性能配置复核

日期：2026-10-21。独立审查为只读；执行验证由主实施方另行运行。当前目录没有Git，因此按明确文件范围复核，不提供不存在的提交范围或远程CI结论。

## 范围与结果

- 历史渠道费用/关闭墓碑核验、本地历史ZIP读取兼容、恢复后隔离API验证：未发现遗留可操作问题。完整历史集及恢复后真实支付/退款已通过；兼容范围、反例与源金融写入0见[非发布验收](../quality/2026-10-20-v1-nonrelease.md)。
- 进程环境三态还原及全部同类调用方、Oracle25.0.5/MySQL8.4.13镜像配置：未发现遗留问题。真实S3启动后后端301项与环境/备份/恢复守卫通过，原空字符串导致的失败日志保留。
- 显式容器性能配置及入口分类计量：普通启动默认16保持，性能模式API128、数据库2CPU/2GiB/cache1GiB，应用2CPU/1GiB/32连接。重复峰值通过，原失败轮保留。四小时结果以实际完成报告为准，不据短测取得R1资格。
- Keycloak26.8.1原发行与受支持Oracle25.0.5组合、构建/Compose/恢复/全配置扫描路径：未发现遗漏；独立恢复身份副本实际运行、完整公钥与真实签名令牌验证通过。运行部署替换与最终业务冒烟须另有执行证据。

## 发现与修复

| 发现 | 修复与验证 |
| --- | --- |
| P2：成功兑换令牌不足以证明PKCE有效 | `identity-runtime.spec.ts`再真实登录取得另一枚授权码，错误verifier必须返回400、`invalid_grant`和明确PKCE错误；正确兑换、签名、nonce及授权码重放检查仍保留。真实执行通过，`keycloak25-oidc.json`记录`wrongPkceVerifierRejected:true`。 |
| P1：身份升级脚本可误停正在长稳的身份服务 | 在任何Docker调用前独占现有`performance-baseline.lock`，持有至升级/回退结束。用当前真实Soak持锁状态验证：升级在任何Docker调用前拒绝，`keycloak25-maintenance-lock-negative.json`通过。 |
| P2：回退使用可变镜像标签，且未核验服务真正恢复 | 回退使用已记录的精确镜像ID；升级与回退共用就绪、完整JWK、属主、运行/无OOM检查。提取实际函数运行反例，错镜像、错公钥、离线、停止、OOM和外项目全部拒绝，`keycloak25-readiness-guards.json`通过。该守卫测试不冒充真实故障回退演练。 |

以上三项均已独立只读复核，当前审查范围无遗留可操作问题。实施方另自查补齐容器启动互斥：新入口在负载/备份持锁时拒绝，旧入口RED、新入口GREEN，纳入verify/CI并独立复核无新增问题。长稳通过后，`.work/upgrade-keycloak25.ps1`与`.work/finish-v1-oct21.ps1`已实际执行：两套身份冷备/完整公钥/实际25.0.5、两角色登录、无锁正常启动及100支付/20退款/400查询冒烟通过。执行结果由`v1-endurance-and-identity-final.json`及原始日志证明。

## 证据

- PKCE：`.work/v1-keycloak25-oidc-pkce-negative-green.log`、`.work/release/keycloak25-oidc.json`。
- 维护互斥与回退守卫：`.work/release/keycloak25-maintenance-lock-negative.json`、`keycloak25-readiness-guards.json`。
- 基础镜像全扫：最终`.work/release/security-20261021T131704Z/summary.json`，Keycloak/MySQL/S3的HIGH/CRITICAL为0，Prometheus官方3.13.5补丁仍有2 HIGH，整体门槛失败；先前10 HIGH失败扫描保留。
- 性能对照及当前长稳：`.work/acceptance/api-capacity-20261021T080740/summary.json`、`.work/acceptance/endurance-20261021T083724/`。

## 最终证据一致性复核

独立只读复核未发现可操作问题。复核时39份验收产物与239份公开源文件，共278份文件的SHA-256及字节数全部匹配；逐行核对234240条长稳请求，38400支付、3840退款、192000查询的数量、四轮尖峰及零未发送/非2xx记录一致。身份升级时序确在长稳完成之后，后续角色登录、业务冒烟及原生金融数量证据一致。最终应用镜像HIGH为0；Prometheus两个二进制各保留同一漏洞的1条HIGH，整体安全门槛失败及五项未过门槛均与公开报告一致。此次复核未调用Docker、网络请求或负载，也未修改文件；本节由实施方记录复核结果。
