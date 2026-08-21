# Gateway/Worker mTLS 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `subagent-driven-development` 或 `executing-plans` 逐任务实现此计划。步骤使用复选框（`- [ ]`）跟踪进度。

**目标：** 为 Agent Gateway gRPC 链路增加可配置的双向 TLS，并在 Kind 中使用临时 CA、Gateway 服务证书和 Worker 客户端证书完成真实连接验证。

**架构：** Gateway 使用服务端证书并要求客户端证书由配置 CA 签发；Worker 使用 CA 验证 Gateway 并发送客户端证书。Worker CR 增加可选 Secret 挂载，证书路径通过环境变量注入。mTLS 默认关闭，启用时缺少任一路径或 Secret 都导致明确失败。

**技术栈：** Java 17、gRPC Netty shaded、Kubernetes Secret、Helm、OpenSSL、Kind。

## 任务 1：TLS 配置与失败测试

- [x] Gateway TLS 属性校验缺少证书、私钥或 CA 时失败。
- [x] Worker 环境解析在 TLS 开启但缺少客户端材料时失败。
- [x] Worker CR 的 TLS Secret 映射到 Deployment volume 和 volumeMount。

## 任务 2：实现 Gateway/Worker mTLS

- [x] Gateway 使用 `GrpcSslContexts.forServer`、CA trust manager 和 `ClientAuth.REQUIRE`。
- [x] Worker 使用 `GrpcSslContexts.forClient`、CA trust manager 和客户端证书。
- [x] 明文模式保持现有行为，TLS 模式不允许静默降级。

## 任务 3：Kind 证书和部署闭环

- [x] 新增临时 CA、Gateway 服务证书、Worker 客户端证书生成脚本。
- [x] Helm 挂载 Gateway Secret 并注入 TLS 配置。
- [x] Operator 支持 Worker `tlsSecret` 字段并挂载客户端 Secret。

## 任务 4：真实验证与交付

- [x] 在 Kind 生成证书、启用 Gateway TLS、重启两个真实 Worker。
- [x] 验证两个 Worker Ready，并运行 Team 调度冒烟。
- [x] 运行 Maven、Helm、manifest、安全扫描，提交并推送。
