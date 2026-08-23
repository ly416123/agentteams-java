# 项目配额幂等与 Kind 端到端验收

## 范围

本验收只验证项目配额的远程 gRPC 边界和 Control Plane 持久化计数，不修改
Java、POM 或 CI workflow。脚本使用 `kubectl port-forward` 访问 Gateway 的
Quota gRPC 服务，使用 PostgreSQL 快照验证计数是否重复变化。

## 覆盖矩阵

| 场景 | 验收条件 | 稳定摘要 |
|---|---|---|
| acquire 重试 | 相同 `tenant/project/idempotency_key` 重试返回相同 reservation，`concurrent/daily_calls/daily_tokens` 只增加一次 | `KIND_QUOTA_ACQUIRE_IDEMPOTENCY_OK` |
| release 重试 | 相同 reservation 和 release 幂等键重试返回成功，current concurrent 只减少一次 | `KIND_QUOTA_RELEASE_IDEMPOTENCY_OK` |
| 拒绝与超时 | 并发上限返回 `CONCURRENT_CALLS`；过期 deadline 返回 `DEADLINE_EXCEEDED` | `KIND_QUOTA_REJECTION_OK` |
| tenant/project 隔离 | 主 scope 被占用时，跨 tenant 和跨 project scope 仍能 acquire | `KIND_QUOTA_SCOPE_ISOLATION_OK` |
| 清理 | 所有测试 reservation release 后 current concurrent 为 0 | `KIND_QUOTA_E2E_OK` |

## 运行方式

先确保 Kind 集群、Gateway、PostgreSQL 已部署，并在本机安装 `grpcurl`：

```bash
python scripts/run-kind-quota-recovery.py
```

可通过 `GRPCURL_BIN` 或 `--grpcurl-bin` 指定 grpcurl 路径；可通过
`--namespace`、`--postgres-pod`、`--gateway-service` 和 scope 参数适配本地集群。
脚本默认使用 `tenant-a/project-a`，并额外验证 `tenant-b/project-b` 与
`tenant-a/project-a-isolated` 两个隔离 scope。

脚本会在测试前将这三个策略的当天计数重置为零，结束时释放所有本次创建的
reservation。运行账号需要能执行 PostgreSQL Pod 的 `psql` 和 Gateway Service
的 port-forward；Gateway 端口默认是 9090。

## 当前限制

本脚本只验证已经部署的 Quota gRPC 服务；它不会安装 grpcurl、创建 Kind 集群，
也不会修改 CI workflow。若要把它纳入 CI，应在独立变更中准备 grpcurl，并在
Kind recovery job 的 Worker 注册完成后调用该脚本。
