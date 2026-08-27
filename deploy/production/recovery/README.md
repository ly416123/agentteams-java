# 生产恢复演练入口

本目录提供恢复演练的参数预检、恢复后元数据引用校验和显式恢复编排入口。脚本不连接集群、不读取对象内容，也不输出备份内容或环境凭据；真正的数据库、对象存储、NATS 和入口操作由环境拥有的 hook 执行，并由值班人与变更审批人共同确认。

## 恢复步骤

1. 创建隔离的恢复目标，确认写入口、Scheduler、Outbox Relay 和 Operator 写操作已暂停。
2. 从环境拥有的备份目录取得备份标识、UTC 恢复时间点、对象存储 endpoint 和已签名发布清单 digest。命令行只传这些元数据；连接凭据通过运行环境注入。
3. 执行预检：

   ```bash
   ./deploy/production/recovery/preflight.sh \
     --environment production \
     --backup-id pg-20260827-0100 \
     --restore-point 2026-08-27T01:00:00Z \
     --endpoint s3://backup.example/agentteams \
     --manifest-digest sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
   ```

   只有看到 `RECOVERY_PREFLIGHT_OK` 才能进入恢复动作。示例值仅用于说明格式，不代表真实环境配置。

4. 按平台批准的版本恢复 PostgreSQL、对象存储引用和 NATS 持久化状态；运行 Flyway validate，禁止在恢复过程中跨多个应用版本自动迁移。
5. 将恢复得到的引用元数据（仅包含 ID 和关系字段）写入临时受控文件，执行：

   ```bash
   python3 deploy/production/recovery/consistency-check.py --input /path/to/metadata.json
   ```

   只有看到 `RECOVERY_CONSISTENCY_OK` 才能逐步启动 Control Plane、Gateway、Operator、Worker 和 Manager，并观察重复抑制与 Outbox 重放。

6. 通过任务、配置、配额、Sandbox 和通知冒烟后，恢复入口，记录实际 RPO/RTO、发布清单 digest、校验结果和审批人。

## 编排入口

需要执行平台恢复时，使用 `restore.sh`。默认只做参数预检和元数据一致性
校验；只有显式提供 `--execute`、匹配的 `RECOVERY_APPROVAL_ID`，并为全部
阶段设置环境拥有的可执行 hook 才会执行恢复。hook 只通过环境变量接收备份
元数据，标准输出和错误输出会被丢弃：

```bash
RECOVERY_APPROVAL_ID=change-123 \
RECOVERY_PAUSE_ENTRYPOINT_COMMAND=/platform/recovery/pause-entrypoint \
RECOVERY_PAUSE_SCHEDULER_COMMAND=/platform/recovery/pause-scheduler \
RECOVERY_RESTORE_POSTGRES_COMMAND=/platform/recovery/restore-postgres \
RECOVERY_RESTORE_OBJECT_STORAGE_COMMAND=/platform/recovery/restore-object-storage \
RECOVERY_RESTORE_NATS_COMMAND=/platform/recovery/restore-nats \
RECOVERY_FLYWAY_VALIDATE_COMMAND=/platform/recovery/flyway-validate \
RECOVERY_CONSISTENCY_CHECK_COMMAND=/platform/recovery/consistency-check \
RECOVERY_START_CONTROL_PLANE_COMMAND=/platform/recovery/start-control-plane \
RECOVERY_START_GATEWAY_COMMAND=/platform/recovery/start-gateway \
RECOVERY_START_OPERATOR_COMMAND=/platform/recovery/start-operator \
RECOVERY_START_WORKER_COMMAND=/platform/recovery/start-worker \
RECOVERY_REPLAY_OUTBOX_COMMAND=/platform/recovery/replay-outbox \
RECOVERY_SMOKE_COMMAND=/platform/recovery/smoke \
RECOVERY_OPEN_ENTRYPOINT_COMMAND=/platform/recovery/open-entrypoint \
RECOVERY_CLOSE_ENTRYPOINT_COMMAND=/platform/recovery/close-entrypoint \
./restore.sh --environment production --backup-id pg-20260827-0100 \
  --restore-point 2026-08-27T01:00:00Z \
  --endpoint s3://backup.example/agentteams \
  --manifest-digest sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  --metadata /controlled/metadata.json --approval-id change-123 --execute
```

阶段顺序固定为：暂停入口与写操作、恢复 PostgreSQL/对象存储/NATS、Flyway
validate、一致性校验、逐步启动服务、重放 Outbox、冒烟，最后才开放入口。
任一阶段失败都会调用 `RECOVERY_CLOSE_ENTRYPOINT_COMMAND` 并返回
`RECOVERY_RESTORE_FAIL`；入口 hook 未配置或不可执行时，脚本不会开始恢复。
这只是受控平台的编排契约，不等同于已完成真实 PITR/RPO/RTO 演练。

## 安全边界

- 脚本采用 fail-closed：环境、备份 ID、UTC 时间、对象 endpoint 或清单 digest 任一不符合格式即停止。
- endpoint 仅允许无用户信息、无查询参数和无片段的 `s3://` 或 `https://` 地址；脚本不发起网络请求。
- 命令行与日志不得包含数据库导出、对象载荷、集群凭据、口令、令牌或私钥。认证材料必须由外部密钥管理和运行环境提供。
- 一致性校验只读取八类记录的稳定 ID 与显式引用字段，忽略业务载荷、事件内容、配置值和对象内容。
- 任何孤立 Task/Attempt/Artifact、未知 Config Snapshot、失联 Quota/Sandbox 或无效 Outbox aggregate 都会阻止继续；失败时保持入口关闭。
- 预检和一致性校验不会删除、修复或重放数据。演练输出应保存到受控审计系统，不要提交到仓库。
