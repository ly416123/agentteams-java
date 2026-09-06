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

待在 L5（`192.168.1.16`）或等价 Linux/KVM 环境执行真实服务实验后补录原始 JSON、JFR 文件、环境信息和灰度决定。当前 L5 可 SSH/K3s 访问，但未安装 JDK/Maven；代码验证使用 Java 21 Maven 容器完成。
