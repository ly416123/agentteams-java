# Kind 本地基础设施闭环实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（- [x]）语法来跟踪进度。

**目标：** 将当前可运行但易丢数据、缺少 QwenPaw 和观测能力的 Kind 环境补齐为可重复安装、可持久化、可验收的本地开发闭环。

**架构：** PostgreSQL、NATS JetStream、MinIO 和 QwenPaw 使用 StatefulSet/Deployment、PVC、固定镜像和内部 Service。Prometheus/Grafana 使用独立的开发清单，通过 Spring Boot Prometheus endpoint 抓取 Control Plane/Gateway 指标；入口使用固定 NodePort 和 Ingress 资源，便于 Kind 端口映射。生产 OIDC、mTLS、KMS 和外部托管数据库不在本地伪造，而通过现有 Secret/环境变量注入。

**技术栈：** Kind、Kubernetes v1.36.1、Helm、PostgreSQL 16、NATS JetStream 2.10、MinIO、QwenPaw v2.1.0、Prometheus、Grafana、Ingress-NGINX、Bash。

---

### 任务 1：为基础依赖增加持久化和 QwenPaw 服务

**文件：**
- 修改：deploy/kind-dev-infra.yaml
- 修改：deploy/helm/kind-values.yaml
- 修改：README.md

- [x] 步骤 1：编写清单验收测试

使用 Python/PyYAML 解析清单，断言 PostgreSQL、NATS、MinIO 存在 PVC 挂载，QwenPaw 使用固定镜像、8088 Service 和三个工作目录挂载。

- [x] 步骤 2：运行测试确认当前清单失败

运行：python3 scripts/validate-kind-infra.py

预期：失败，报告现有 PostgreSQL/NATS/MinIO 使用 emptyDir 且缺少 QwenPaw。

- [x] 步骤 3：实现最小基础设施变更

将 PostgreSQL、NATS、MinIO 改为单副本 StatefulSet，并为数据目录声明 ReadWriteOnce PVC；NATS 增加 --store_dir /data/jetstream。新增 QwenPaw Deployment、Service、三个 PVC/挂载目录和 TCP 探针，镜像固定为 agentscope/qwenpaw:v2.1.0。

- [x] 步骤 4：运行清单验收测试

运行：python3 scripts/validate-kind-infra.py

预期：输出 KIND_INFRA_OK。

### 任务 2：增加 Prometheus/Grafana 本地观测栈

**文件：**
- 创建：deploy/kind-observability.yaml
- 创建：scripts/validate-observability.py
- 修改：deploy/helm/kind-values.yaml
- 修改：README.md

- [x] 步骤 1：编写失败验收测试

断言 Prometheus 配置抓取 Control Plane/Gateway 的 /actuator/prometheus，Prometheus/Grafana 使用 PVC，Grafana 自动配置 Prometheus 数据源。

- [x] 步骤 2：运行测试确认失败

运行：python3 scripts/validate-observability.py

预期：文件不存在，测试失败。

- [x] 步骤 3：实现清单

新增固定版本 Prometheus、Grafana Deployment/Service/PVC、Prometheus ConfigMap 和 Grafana datasource provisioning ConfigMap；Grafana 使用开发 Secret 的管理员密码占位值，不接入生产 Secret 管理。

- [x] 步骤 4：运行验收测试

运行：python3 scripts/validate-observability.py

预期：输出 OBSERVABILITY_OK。

### 任务 3：增加统一入口和 Kind 安装脚本

**文件：**
- 修改：deploy/kind-config.yaml
- 创建：deploy/kind-ingress.yaml
- 创建：deploy/install-kind-dev.sh
- 创建：scripts/validate-kind-manifests.py
- 修改：README.md

- [x] 步骤 1：编写失败验收测试

断言 Kind 配置包含 HTTP/HTTPS 端口映射，入口资源覆盖 Control Plane、Gateway、QwenPaw、Prometheus 和 Grafana，安装脚本按依赖顺序执行。

- [x] 步骤 2：运行测试确认失败

运行：python3 scripts/validate-kind-manifests.py

预期：失败，报告入口和一键脚本缺失。

- [x] 步骤 3：实现入口和安装脚本

新增 Ingress-NGINX 安装脚本调用官方 Helm 仓库，固定 NodePort 端口；新增本地 Ingress 规则和 Kind 端口映射。安装脚本检查 Colima/Docker、Kind、kubectl、Helm，按“基础依赖→bootstrap→观测→Ingress→应用镜像→应用 Helm”顺序执行，不自动删除或重建已有集群。

- [x] 步骤 4：运行静态验收

运行：python3 scripts/validate-kind-manifests.py、helm lint deploy/helm/agentteams-java。

预期：输出 KIND_MANIFESTS_OK，Helm lint 通过。

### 任务 4：增加 PostgreSQL 和对象存储备份脚本

**文件：**
- 创建：deploy/backup/backup-kind.sh
- 创建：deploy/backup/restore-kind.sh
- 修改：deploy/backup/README.md
- 创建：scripts/validate-backup-scripts.sh

- [x] 步骤 1：编写失败验收

断言备份脚本导出 PostgreSQL custom dump、记录 SHA-256，并拒绝空备份目录；恢复脚本要求显式 --confirm。

- [x] 步骤 2：运行测试确认失败

运行：bash scripts/validate-backup-scripts.sh

预期：脚本不存在，测试失败。

- [x] 步骤 3：实现脚本

备份脚本通过 kubectl exec statefulset/postgresql 导出数据库，并通过本机 mc（或显式 MC_BIN）镜像 MinIO bucket；恢复脚本只在 --confirm 下执行 pg_restore 和对象回传。两者都校验 namespace、文件路径和必需命令，不删除已有备份。

- [x] 步骤 4：运行脚本验收

运行：bash scripts/validate-backup-scripts.sh。

预期：输出 BACKUP_SCRIPTS_OK。

### 任务 5：部署并验证完整本地闭环

**文件：**
- 修改：README.md
- 修改：docs/superpowers/plans/2026-08-18-kind-foundation-completion.md

- [x] 步骤 1：准备镜像

运行 source deploy/dev-env.sh、./deploy/pull-kind-node-image.sh，并通过当前可用镜像代理预加载 PostgreSQL、NATS、nats-box、MinIO、mc、QwenPaw、Prometheus、Grafana 和 Ingress-NGINX 镜像；官方镜像名保持在 Kubernetes 清单中。

- [x] 步骤 2：执行一键安装

运行：./deploy/install-kind-dev.sh。

预期：所有 StatefulSet/Deployment Ready，bootstrap Job Complete，Ingress-NGINX Ready，Helm release 为 deployed。

- [x] 步骤 3：执行功能验收

检查 PostgreSQL/NATS/MinIO/QwenPaw/TCP 服务，访问 Prometheus targets 和 Grafana health，执行 Control Plane actuator health 和 API 冒烟请求。

- [x] 步骤 4：执行回归测试

运行 source deploy/dev-env.sh && mvn -q -Dmaven.repo.local=/private/tmp/agentteams-java-m2 test、git diff --check。

预期：Maven 全量通过，工作区差异无空白错误。
