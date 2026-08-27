# AgentTeams Java 生产交付与可靠性闭环设计

**日期：** 2026-08-26
**状态：** 批次 A 已完成 Helm/NetworkPolicy 基础；批次 B 已完成签名制品、入口/egress 和恢复安全闸门第一纵切，真实生产平台门禁待后续
**优先级：** P0/P1
**代码基线：** `93d99fb`

**批次 B 增量（2026-08-28）：** 已新增 digest-pinned Release Manifest、CycloneDX/SLSA attestation、Cosign keyless 发布/Chart 签名和 production Environment 晋级入口；Helm 已支持默认关闭的 Ingress/Gateway API，NetworkPolicy 已支持 CIDR/PROXY/PLATFORM 契约，恢复目录已提供不读取 Secret/业务载荷的 preflight 与一致性校验；晋级入口已增加可配置 Prometheus 健康/错误预算门禁，并在证据缺失或预算超限时自动回滚到晋级前 Helm revision。当前剩余真实 GHCR/受控环境流量 Canary、平台 Prometheus 选择、CNI egress 和 PITR 演练。

## 1. 目标

本规格使生产示例从“字段正确、可人工部署”升级为“网络可达、Secret 可轮换、制品可追溯、部署可回滚、数据可恢复”的交付闭环。

本规格不在仓库内安装托管 PostgreSQL、NATS、S3、企业 IdP、Vault 或生产 Matrix Homeserver，而是定义它们必须满足的契约、预检、监控和演练入口。

## 2. 当前断点（基于 `fd721d3`）

- 生产 values 已声明外部 PostgreSQL、NATS、S3、OIDC、Matrix、模型和 OTLP 的 CIDR 入口，仍缺 PROXY/PLATFORM 模式的完整运行时接线；
- 标准 Kubernetes NetworkPolicy 不能按域名放行，当前生产配置没有 CIDR/端口或统一 Egress Proxy 契约；
- External Secrets、cert-manager 和 Reloader 仍主要是文档约定，没有统一 Ready/轮换门禁；
- 当前 GitHub Actions 仍以 CI 和 Kind 为主，缺少签名镜像、SBOM、provenance 和环境晋级流程；
- 生产 values 使用人工替换的 release tag，没有镜像 digest、SBOM、签名、provenance 和环境晋级；
- PostgreSQL/MinIO 恢复脚本已覆盖 Kind 验收，生产 RPO/RTO、PITR 和恢复演练没有自动化证据；
- Chart 只创建 ClusterIP Service，Ingress/Gateway API、TLS 终止、DNS、WAF 和入口限流由环境隐式承担；
- mTLS、OIDC 和 Matrix 已有开发验收，但生产证书轮换、外部 IdP 和 Homeserver 恢复仍缺少发布门禁。

## 3. 生产环境责任边界

| 能力 | AgentTeams 仓库负责 | 平台负责 |
|---|---|---|
| PostgreSQL | schema、连接、健康、备份验证和恢复后一致性检查 | HA、PITR、WAL、加密、跨 AZ、备份保留 |
| NATS JetStream | stream/consumer 契约、重连、Outbox 恢复和积压指标 | 集群、磁盘、备份、跨 AZ 和账号权限 |
| S3/MinIO | bucket/key 契约、checksum、预签名 URL、对象完整性验证 | 版本化、加密、复制、保留和访问日志 |
| OIDC | issuer/JWKS/audience/claim 校验和轮换验收 | IdP HA、用户生命周期、MFA 和签名密钥 |
| Secret | 引用、挂载、状态、轮换触发和脱敏 | Vault/KMS/External Secrets、SecretStore 和授权 |
| mTLS | 证书路径、身份验证、重连和到期指标 | CA、签发、吊销、轮换和 Secret 同步 |
| OTel/Prometheus | 指标、Trace、ServiceMonitor、Rule 和预检 | Collector、存储、Alertmanager、Grafana 和长期保留 |
| Matrix | AppService 协议、幂等 Inbox/Outbox 和重放 | Homeserver、持久化、备份、域名和 Token 下发 |
| 入口 | Service、可选 Ingress/Gateway 模板和健康端点 | DNS、WAF、LB、外部证书和流量策略 |

## 4. NetworkPolicy 设计

### 4.1 标准模式

标准 Kubernetes 模式只支持：

- namespaceSelector + podSelector；
- `ipBlock.cidr`；
- 协议和端口。

不在标准模板中伪造 FQDN NetworkPolicy。外部域名有 3 种选择：

1. 明确稳定 CIDR 和端口；
2. 所有外部访问经过固定 Egress Proxy；
3. 集群使用 Cilium 等支持 FQDN Policy 的平台扩展，由平台 overlay 提供。

推荐生产默认使用 Egress Proxy 或稳定私网 CIDR。

### 4.2 Values schema

```yaml
networkPolicy:
  enabled: true
  egressMode: CIDR # CIDR | PROXY | PLATFORM
  external:
    postgresql: [{cidr: 10.10.0.0/24, port: 5432}]
    nats: [{cidr: 10.20.0.0/24, port: 4222}]
    objectStorage: [{cidr: 10.30.0.0/24, port: 443}]
    otlp: [{cidr: 10.40.0.0/24, port: 4318}]
    oidc: [{cidr: 10.50.0.0/24, port: 443}]
    matrix: [{cidr: 10.60.0.0/24, port: 443}]
    modelProviders: [{cidr: 10.70.0.0/24, port: 443}]
  proxy:
    host: egress-proxy.platform.svc
    port: 8443
```

校验规则：

- `enabled=true` 时必须显式选择模式；
- CIDR 模式下，启用的外部依赖必须至少有一条目标；
- `0.0.0.0/0` 和 `::/0` 默认拒绝，只有 `allowPublicInternet=true` 且生产校验显式豁免才允许；
- PROXY 模式只放行 Proxy，应用连接配置必须使用 Proxy；
- PLATFORM 模式不生成外部 egress rule，但校验要求填写平台 Policy Artifact 名称和审计引用。

### 4.3 组件最小网络

- Control Plane：PostgreSQL、NATS、S3、OIDC、Matrix、OTLP、受控 Model/MCP/Skill endpoint；
- Gateway：PostgreSQL、NATS、OTLP 和 Worker 入站 gRPC；
- Manager：Control Plane、Gateway Quota、Model Provider、OTLP；
- Operator：Kubernetes API；
- Worker：Gateway、Control Plane 配置/Artifact、QwenPaw/Model、OTLP，以及 Sandbox 策略允许的目标；
- Sandbox：默认 DNS-only；Task Policy 显式授权后才增加受控出口。

## 5. Secret、证书与配置轮换

### 5.1 平台契约

Chart 只接受 `existingSecret` 和 key 名，不接受 Secret value。生产预检必须验证：

- ExternalSecret/SecretProviderClass Ready；
- 目标 Secret 存在且包含要求的 key；
- ServiceAccount 不能读取无关 Secret；
- Reloader 或等价控制器已安装，或者应用支持热重载；
- 证书 NotBefore/NotAfter、SAN 和 CA 链满足要求。

`ExternalSecret`、`SecretStore`、Vault policy 和 KMS policy 归环境或平台仓库所有，
AgentTeams Chart 不创建这些高权限对象。若启用 Secret presence resolver，Control
Plane 只获得当前 namespace 内指定稳定 Secret 名称的 `get` 权限，不授予
`list/watch`，API、日志和审计也不得返回原始 `credentialRef` 或 Secret 内容。
ExternalSecret 非 Ready、超过两个刷新周期未成功或目标 key 缺失时，发布门禁失败，
但不得删除最后一个已验证的目标 Secret。

### 5.2 轮换策略

- API Key/endpoint：先构建并探测新 Provider，成功后原子切换，失败保留旧连接；
- mTLS：双 CA 重叠，先轮换服务端，再逐批轮换客户端，旧证书全部过期后移除旧 CA；
- OIDC：新旧 JWKS `kid` 重叠至少覆盖最长 Token 生命周期和缓存窗口；
- Matrix token：Homeserver 与 AppService 在重叠窗口内完成切换，旧 Token 撤销后执行负向验收；
- 数据库/NATS/S3 凭据：使用稳定 Secret 名触发滚动更新，连接池关闭旧连接并建立新连接。

轮换不得依赖修改 Git 中的 values。

## 6. 镜像构建、发布与晋级

### 6.1 制品身份

每个组件镜像使用：

镜像仓库固定使用 `ghcr.io/ly416123/agentteams-组件名`。发布标签采用
`vMAJOR.MINOR.PATCH`，生产引用使用该 OCI Manifest 实际返回的 SHA-256 digest，
不允许人工编造或复制其他组件的 digest。

生产部署只使用 digest。Tag 用于人类发现，不作为生产不可变标识。

组件包括：Control Plane、Gateway、Worker、Operator、Manager 和 Task Sandbox。基础镜像固定到 digest；Maven 依赖继续由 lock/版本管理和依赖扫描控制。

### 6.2 Release workflow

新增 `.github/workflows/release.yml`：

1. 验证 tag 指向受保护分支提交；
2. 执行 Java、集成、Helm 和 Kind 测试；
3. 使用 BuildKit/buildx 构建多组件镜像；
4. 生成 CycloneDX/SPDX SBOM；
5. 执行依赖、镜像和 Secret 扫描；
6. 推送到 GHCR；
7. 生成 SLSA provenance；
8. 使用 GitHub OIDC keyless Cosign 签名；
9. 生成包含所有 digest、Chart version、Git SHA 和 SBOM 引用的 `release-manifest.json`；
10. 打包并签名 Helm Chart；
11. 上传 Release Artifact。

所有第三方 Actions 固定到完整 commit SHA。下载的 CLI 校验发布方 checksum 或签名。

### 6.3 环境晋级

新增 `.github/workflows/promote.yml`，输入只能是已签名 `release-manifest.json` 和目标环境：

- 验证镜像、Chart、SBOM、签名和 provenance；
- 替换 production overlay 中的 digest，不重建镜像；
- 执行预检；
- 部署 Canary；
- 验证健康、任务闭环、OTel、配置 ACK 和错误预算；
- 按批次扩大；
- 失败自动回滚到上一签名 manifest。

生产环境使用 GitHub Environment 审批或等价平台门禁。发布工作流不读取应用业务 Secret，只触发环境内 Deployment。

## 7. Helm 生产配置

新增 `values.schema.json`，至少校验：

- 所有生产镜像使用 digest；
- 外部 endpoint 使用允许的 scheme，不能包含 userInfo、fragment 或 Secret；
- `existingSecret` 非空，禁止出现疑似 Key/Token 值；
- replicas、PDB、resources、securityContext、NetworkPolicy 和 OTLP 配置一致；
- Sandbox 启用时 Kubernetes Provider、RuntimeClass 和镜像 digest 完整；
- AgentScope/Manager 启用时对应镜像、模型 Secret 和回滚默认值完整；
- OIDC/mTLS 启用时 issuer/JWKS/audience/证书路径完整；
- production values 禁止 `latest`、`RELEASE_TAG` 和 `0.0.0.0/0` 未豁免规则。

生产模板提供组件级 resources、topology spread、anti-affinity、securityContext 和独立 PDB。

## 8. 入口与 TLS 终止

Chart 提供可选模式：

```yaml
ingress:
  enabled: false
  mode: INGRESS # INGRESS | GATEWAY_API
  className: nginx
  host: api.example.com
  tlsSecretName: agentteams-api-tls
  annotations: {}
```

- 只暴露 Control Plane 公共 API 和 Manager 公共 API；
- Gateway Agent gRPC 默认使用内部 LoadBalancer/ClusterIP 和 mTLS，不与公共 API 共用未验证入口；
- `/internal`、Actuator 非必要端点、Operator 和数据库不公开；
- 外部 LB/WAF 的客户端 IP 信任只接受显式可信代理 CIDR；
- 限流和请求体上限必须与应用层限制一致。

平台也可以关闭 Chart 入口并使用自己的 Gateway overlay。

## 9. 生产备份与恢复

### 9.1 RPO/RTO 基线

初始目标：

- PostgreSQL：RPO ≤ 5 分钟，RTO ≤ 60 分钟；
- S3 Artifact/Config：RPO ≤ 15 分钟，RTO ≤ 4 小时；
- NATS：允许通过 PostgreSQL Outbox 重建未投递业务事件，但 JetStream consumer 状态仍需平台备份；
- Matrix：不作为业务事实源，恢复后通过持久化 Outbox 重放通知。

目标可由部署环境提高，但不得在未演练时宣称达成。

### 9.2 备份契约

- PostgreSQL：物理/连续归档或托管 PITR，备份加密、跨故障域保存、定期校验；
- S3：版本化、服务端加密、生命周期和可选 Object Lock；
- NATS：JetStream FileStore、多副本和 stream 配置导出；
- Secret：由 Secret Manager 自己备份，仓库不导出明文；
- Helm release manifest、CRD 和生产配置的非 Secret 部分保存在签名 Release Artifact 中。

### 9.3 恢复编排

新增 `deploy/production/recovery/`：

1. `preflight`：验证目标环境、版本和备份元数据；
2. 暂停写入口、Scheduler、Outbox Relay 和 Operator 写操作；
3. 恢复 PostgreSQL 到指定时间；
4. 恢复/挂载 S3 版本并验证对象引用；
5. 恢复 NATS 或重建 stream/consumer；
6. 运行 Flyway `validate`，禁止恢复过程中自动跨多个应用版本迁移；
7. 运行任务、Artifact、Config、Quota、Sandbox 和 Outbox 一致性校验；
8. 逐步恢复 Control Plane、Gateway、Operator、Worker 和 Manager；
9. 重放 Outbox，观察重复抑制；
10. 恢复入口并生成演练报告。

脚本只接收备份 ID、时间点和非 Secret endpoint；凭据由环境注入。任何失败保持入口关闭并输出脱敏诊断。

### 9.4 演练

- 每月执行自动恢复到隔离环境；
- 每季度执行含入口切换的完整演练；
- 报告记录备份 ID、Git SHA、release manifest digest、实际 RPO/RTO、一致性结果和审批人；
- 演练 Artifact 不包含数据库 dump、对象内容或 Secret。

## 10. 发布与回滚

部署顺序：

1. CRD 和向后兼容数据库迁移；
2. Control Plane Canary；
3. Gateway；
4. Operator；
5. Manager；
6. Worker 分批 rollout；
7. 启用新功能 Flag。

Expand/Contract 数据库变更至少跨 2 个应用版本：先新增兼容字段/表，再迁移数据，最后在后续版本删除旧结构。回滚不得要求数据库降级。

自动回滚触发条件：

- readiness 持续失败；
- Task/Config/Sandbox 冒烟失败；
- 错误率或延迟超过发布预算；
- Worker Ready 数量或 Gateway 连接数低于安全阈值；
- migration、Secret、NetworkPolicy 或证书预检失败。

## 11. 核心实现文件

预计新增：

- `.github/workflows/release.yml`
- `.github/workflows/promote.yml`
- `deploy/helm/agentteams-java/values.schema.json`
- `deploy/helm/agentteams-java/templates/ingress.yaml`
- `deploy/helm/agentteams-java/templates/gateway-api.yaml`
- `deploy/production/recovery/README.md`
- `scripts/validate-release-manifest.py`
- `scripts/validate-production-network.py`
- `scripts/validate-production-recovery.py`

预计修改：

- `deploy/helm/agentteams-java/values.yaml`
- `deploy/helm/agentteams-java/values-production.example.yaml`
- `deploy/helm/agentteams-java/templates/networkpolicy.yaml`
- 所有 `deploy/docker/*.Dockerfile`
- `deploy/build-images.sh`
- `scripts/validate-production-values.py`
- `scripts/validate-production-endpoints.sh`
- `deploy/production/README.md`
- `deploy/production/observability-runbook.md`

## 12. 验收标准

### L1/L2

- Values schema 拒绝 `latest`、占位 tag、内联 Secret、不完整 OIDC/mTLS 和危险公网 CIDR；
- Release manifest digest、组件集合和签名校验有正负向测试；
- Expand/Contract migration 从当前生产版本和空库都能通过；
- 恢复一致性校验能发现孤立 Artifact、未知 Config binding、泄漏 Quota reservation 和未收敛 Sandbox。

### L3

- production values 完整 Helm lint/template；
- 每个组件只得到所需的外部 egress 和内部 ingress；
- 标准模式不生成虚假的 FQDN NetworkPolicy；
- Pod 安全、资源、PDB、拓扑和镜像 digest 策略通过；
- Ingress/Gateway API 不暴露 `/internal` 或非必要管理端点。

### L4

- 在 Kind 中使用模拟外部 CIDR/Proxy 验证 NetworkPolicy 允许必需依赖并拒绝其他出口；
- 使用本地 Registry 按 digest 安装，篡改 tag 不影响部署制品；
- Canary 失败后自动回滚，Task、Config 和 Outbox 状态保持一致；
- 当前 PostgreSQL/MinIO/NATS 恢复测试继续通过。

### L5

- 生产 Sandbox 镜像按签名 digest 部署；
- gVisor/Kata 节点 RuntimeClass、NetworkPolicy 和镜像验证策略联合通过。

### L6

- 外部 PostgreSQL、NATS、S3、OIDC、OTLP、Matrix、Secret Manager 和模型 endpoint 预检通过；
- Secret、数据库凭据、mTLS 和 OIDC key 轮换无全量中断；
- 从签名 Release Artifact 部署、Canary、扩大和回滚完整通过；
- PostgreSQL PITR、S3 恢复、NATS 重建和 Outbox 重放演练满足实际记录的 RPO/RTO；
- SBOM、签名、provenance、审批、部署和恢复报告可以追溯到同一个 Git SHA 和 release manifest digest。
