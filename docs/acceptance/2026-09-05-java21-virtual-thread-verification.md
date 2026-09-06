# Java 21 / 虚拟线程 SSE 验收记录

## 当前结论

Java 21 基线和 SSE 虚拟线程试点代码已完成并通过自动化测试。虚拟线程开关默认关闭，当前不能据此宣称吞吐或尾延迟已经改善；必须在相同 QwenPaw SSE、数据库和部署资源下完成平台线程与虚拟线程对照实验后，才能决定灰度开启。

## 已验证内容

- Manager 和 HTTP Runtime 仅将阻塞 SSE 响应读取切换到 `Executors.newVirtualThreadPerTaskExecutor()`。
- Manager 的请求/会话信号量、单会话单请求、取消、超时和响应大小限制保持不变。
- Runtime 的任务幂等、取消和终态事件保持不变；JsonLines 进程 Runtime 继续使用固定 stdout/stderr 读取线程。
- `AGENTTEAMS_VIRTUAL_THREADS_ENABLED=false` 为默认值；示例 Worker 也显式保持关闭。
- Java 21 容器验证：Contracts 17、Application Contracts 43、Runtime 66、Manager 179 个测试通过。

## 对照实验命令

先分别部署相同镜像和资源限制，仅切换 `AGENTTEAMS_VIRTUAL_THREADS_ENABLED`，再执行：

```bash
python3 scripts/benchmark-io-concurrency.py \
  --base-url http://<qwenpaw-or-manager> \
  --path /api/console/chat \
  --mode both \
  --warmup-seconds 60 \
  --duration-seconds 300 \
  --runs 3 \
  --output artifacts/java21-virtual-thread-benchmark.json
```

脚本默认固定并发档位为 16、64、128、256；未暴露的 RSS、GC、数据库连接池、NATS、Outbox 和上游错误率指标会记录为 `null`，不会填充估算值。`--dry-run` 可在不访问服务的情况下检查实验参数。

## 灰度门禁

1. 所有并发档位成功率、取消和超时语义与平台线程基线一致。
2. P95/P99 不得恶化；吞吐和资源占用改善必须有三轮重复数据支持。
3. JFR 中不得出现频繁且超过 20ms 的 `jdk.VirtualThreadPinned`；若出现，先移除阻塞临界区再扩大并发。
4. 故障注入需覆盖 NATS 断连、PostgreSQL 重启、QwenPaw 长时间无事件、429/5xx、Worker 重连、Manager 滚动重启和应用关闭。
5. 任一门禁不满足时，保留 Java 21 基线，但保持虚拟线程开关关闭。

## 实验结果

### L5 环境检查（2026-09-06）

- 节点 `192.168.1.16` 为 Linux/amd64，K3s 节点为 `Ready`，默认目标为 `multi-user.target`。
- Manager 和 Worker Pod 均可用，但运行时版本为 Temurin 17.0.20；当前分支的 Java 21 镜像尚未部署。
- Manager/Worker 未注入 `AGENTTEAMS_VIRTUAL_THREADS_ENABLED`，按代码默认值保持关闭。
- QwenPaw `/api/console/chat` 可建立 HTTP 200 SSE 连接，但短测未收到终态事件；因此尚不能把该环境作为正式吞吐和尾延迟基线。

### Demo Worker 灰度启动验证（2026-09-06）

- 当前分支构建的 `agentteams-agent-worker:java21-candidate` 已导入 L5 K3s 镜像缓存。
- 仅对 `l5-demo-qwenpaw-worker` 的 Worker CR 做了可回滚试点；Java 21、开关 `false` 和开关 `true` 两种配置均成功滚动并报告 `Worker is READY`。
- 未执行真实任务压测；验证完成后已恢复原始 Java 17 镜像和默认关闭配置，其他 Worker/Manager 未切换。
- 试点期间出现的 `FAILED_PRECONDITION: connection is no longer current` 来自 Worker 重启时旧 Gateway 连接被新连接替代，随后 Worker 持续报告 READY，未观察到启动失败。

### Demo Manager 灰度启动验证（2026-09-06）

- 已构建并导入 `agentteams-manager:java21-candidate`，镜像架构为 Linux/amd64，容器内 Temurin 版本为 21.0.12。
- 通过 L5 Helm release `agentteams` 做单副本 Manager 灰度，开启 `AGENTTEAMS_VIRTUAL_THREADS_ENABLED=true` 后新 Pod 成功 Ready；日志确认 Java 21.0.12、Flyway 校验 9 个迁移且数据库版本为 9、无需执行迁移，Tomcat 正常监听 8080。
- 灰度未执行正式 SSE 压测：QwenPaw 仍未在短测中产生终态事件，因此不能把本次启动验证当作性能收益证据。
- 首次 Helm upgrade 因 Control Plane 镜像存在历史 `kubectl-set` 字段管理冲突而失败，随后使用 `--force-conflicts` 回滚到 revision 40；最终 release revision 42 为 `deployed`，Manager 恢复 `ghcr.io/ly416123/agentteams-manager:l5-scope-final`、Temurin 17.0.20，虚拟线程环境变量未启用，Control Plane/Gateway/Operator/Manager 均为 Ready。

待确认 QwenPaw 上游能够稳定产生终态事件后，再补录正式压测原始 JSON、JFR 文件、环境信息和灰度决定。当前代码验证和启动灰度均已使用 Java 21 候选镜像完成，但生产组件已恢复原始 Java 17/默认关闭状态。
