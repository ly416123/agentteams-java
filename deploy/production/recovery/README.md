# 生产恢复演练入口

本目录提供恢复演练的参数预检、恢复后元数据与对象一致性校验、显式恢复编排入口。通用校验器只读取元数据；`object-inventory.py` 会流式读取恢复目标的本地对象镜像来计算 SHA-256，但不会输出或保存对象正文。数据库、对象存储、NATS 和入口操作由环境拥有的 hook 执行，并由值班人与变更审批人共同确认。

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

4. 按平台批准的版本恢复 PostgreSQL、对象存储和 NATS 持久化状态；运行 Flyway validate，禁止在恢复过程中跨多个应用版本自动迁移。
5. 恢复 DB 和对象存储后，由环境拥有的 hook 导出恢复目标的规范化引用和对象清单。引用文件必须只包含八类既有 ID/关系集合及 `object_references`；每行只可包含 ID 和外键关系字段，不能夹带 payload。对象清单必须只包含 `objects` 数组，并列出恢复目标镜像中的每个对象。格式如下：

   ```json
   {
     "object_references": [
       {"path": "tasks/example/result.pdf", "size_bytes": 4, "sha256": "...64位十六进制摘要..."}
     ]
   }
   ```

   ```json
   {
     "objects": [
       {"path": "tasks/example/result.pdf", "size_bytes": 4, "sha256": "...64位十六进制摘要..."}
     ]
   }
   ```

   引用导出必须覆盖本次恢复范围内所有拥有对象引用的数据库，包括独立会话域。`task_files` 的 INPUT 仅是会话域文件引用，必须由会话域导出器提供其真实对象键。内部 `task-file:<id>` 等逻辑引用必须解析到实际拥有的对象记录，不能作为存储键猜测。可从 Control Plane 导出的记录至少包括：状态为 `AVAILABLE` 的 `artifacts`，`config_files` 和状态为 `COMPLETED` 的 `config_uploads`，上传完成的技能包，以及状态为 `AVAILABLE` 的任务 OUTPUT 文件。只要恢复范围中存在对象引用，不能用空数组跳过它们。

   同一对象键可被多行引用，但其大小和 SHA-256 必须一致。清单中存在未被当前数据库引用的对象是允许的（例如独立保留的会话对象）；每个数据库引用都必须命中对象清单，且大小与 SHA-256 精确匹配。

   对从恢复目标镜像出的本地只读对象树生成清单：

   ```bash
   python3 deploy/production/recovery/object-inventory.py \
     --root /controlled/restored-object-mirror \
     --output /controlled/recovered-object-inventory.json
   ```

   生成器拒绝符号链接，输出只含相对路径、字节数和 SHA-256，文件权限为 `0600`。对象镜像必须来自恢复目标，而不是恢复前的源桶或备份副本。

   手动校验时执行：

   ```bash
   python3 deploy/production/recovery/consistency-check.py \
     --input /controlled/recovered-db-references.json \
     --object-inventory /controlled/recovered-object-inventory.json
   ```

   只有看到 `RECOVERY_CONSISTENCY_OK` 才能逐步启动 Control Plane、Gateway、Operator、Worker 和 Manager，并观察重复抑制与 Outbox 重放。失败输出不包含对象路径、摘要或载荷。

6. 通过任务、配置、配额、Sandbox 和通知冒烟后，恢复入口，记录实际 RPO/RTO、发布清单 digest、校验结果和审批人。

## 编排入口

需要执行平台恢复时，使用 `restore.sh`。默认只做参数预检和已存在清单的一致性校验；只有显式提供 `--execute`、匹配的 `RECOVERY_APPROVAL_ID`，并为全部阶段设置环境拥有的可执行 hook 才会执行恢复。hook 的标准输出和错误输出会被丢弃。

execute 模式下 `--metadata` 与 `--object-inventory` 指向尚不存在、父目录可写的受控输出文件。恢复后的 `RECOVERY_CONSISTENCY_CHECK_COMMAND` hook 收到 `RECOVERY_METADATA_FILE` 与 `RECOVERY_OBJECT_INVENTORY_FILE`，从已恢复的目标导出两份清单。编排器将文件权限设为 `0600` 并自行运行通用校验器；通过前不会启动服务：

仓库提供 `export-recovery-metadata.py` 生成可直接交给通用校验器的完整元数据文件：包含八类 ID/FK 集合和对象引用。它从恢复后的 Control Plane 数据库导出 tasks、attempts、artifacts、config bindings/snapshots、token ledger reservations、task sandboxes，以及映射范围内的 Outbox aggregate；再从 Control Plane 与 Manager 数据库导出对象引用。Outbox 关系检查仅覆盖校验器明确定义的核心 aggregate 类型；agent、team、model、worker operation 等不在此八类关系图内，因此不会混入未知聚合后误报。payload 不会导出。

对象引用导出覆盖 AVAILABLE artifacts、配置文件和已完成上传、AVAILABLE task OUTPUT、已完成 skill package、task result artifacts 与 Manager `conversation_files`。它需要匹配该备份恢复点的**源对象 inventory**；会校验源 inventory 权限、路径和大小，验证数据库内已有摘要，并将 Manager 当前未持久化的摘要绑定到同一备份点的源 inventory。缺少键、大小不一致、已有摘要不一致或引用冲突都会失败关闭。源 inventory 必须在备份时从该恢复点的对象镜像生成并与数据库备份 ID 一起保留；不能用恢复后 inventory 反向补齐源摘要。

恢复 hook 可在恢复数据库后调用以下命令，生成完整 checker-ready 文件。数据库连接参数通过 `RECOVERY_CONTROL_PLANE_PGHOST`、`RECOVERY_CONTROL_PLANE_PGDATABASE`、`RECOVERY_CONTROL_PLANE_PGUSER` 及可选的 `RECOVERY_CONTROL_PLANE_PGPASSWORD` / TLS 变量提供；Manager 使用相同后缀的 `RECOVERY_MANAGER_PG*` 变量。密码只在进程环境中传给 `psql`，不进入参数或日志。查询强制 PostgreSQL 只读事务和 30 秒 statement timeout。

```bash
python3 deploy/production/recovery/export-recovery-metadata.py \
  --source-inventory /controlled/backup-source-object-inventory.json \
  --output /controlled/recovered-db-references.json
```

底层 `export-object-references.py` 可单独生成对象引用片段；恢复 hook 应优先使用完整导出器。`scripts/run-kind-object-reference-integrity.py` 是 Kind 集成验证脚本，会创建 helper Pod、插入/删除测试行并写入/删除 MinIO 对象，不能作为恢复清单导出器使用。

```bash
RECOVERY_APPROVAL_ID=change-123 \
RECOVERY_PAUSE_ENTRYPOINT_COMMAND=/platform/recovery/pause-entrypoint \
RECOVERY_PAUSE_SCHEDULER_COMMAND=/platform/recovery/pause-scheduler \
RECOVERY_RESTORE_POSTGRES_COMMAND=/platform/recovery/restore-postgres \
RECOVERY_RESTORE_OBJECT_STORAGE_COMMAND=/platform/recovery/restore-object-storage \
RECOVERY_RESTORE_NATS_COMMAND=/platform/recovery/restore-nats \
RECOVERY_FLYWAY_VALIDATE_COMMAND=/platform/recovery/flyway-validate \
RECOVERY_CONSISTENCY_CHECK_COMMAND=/platform/recovery/export-restored-metadata \
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
  --metadata /controlled/recovered-db-references.json \
  --object-inventory /controlled/recovered-object-inventory.json \
  --approval-id change-123 --execute
```

阶段顺序固定为：暂停入口与写操作、恢复 PostgreSQL/对象存储/NATS、Flyway validate、从恢复目标导出并校验清单、逐步启动服务、重放 Outbox、冒烟，最后才开放入口。execute 模式清单路径必须在开始前不存在，防止误用旧恢复结果。空缺、过期或不完整导出不是有效恢复证明。任一阶段失败都会调用 `RECOVERY_CLOSE_ENTRYPOINT_COMMAND` 并返回 `RECOVERY_RESTORE_FAIL`；入口 hook 未配置或不可执行时，脚本不会开始恢复。这只是受控平台的编排契约，不等同于已完成真实 PITR/RPO/RTO 演练。

## 安全边界

- 脚本采用 fail-closed：环境、备份 ID、UTC 时间、对象 endpoint 或清单 digest 任一不符合格式即停止。
- endpoint 仅允许无用户信息、无查询参数和无片段的 `s3://` 或 `https://` 地址；脚本不发起网络请求。
- 命令行与日志不得包含数据库导出、对象载荷、集群凭据、口令、令牌或私钥。认证材料必须由外部密钥管理和运行环境提供。
- 元数据引用与 inventory 只包含对象相对路径、字节数和 SHA-256，不包含对象正文、配置值、原始提示词或凭据；校验器拒绝未列入 schema 的额外字段。临时清单需限制为 `0600` 并在审计留存策略允许的期限内清理。
- 一致性校验读取八类记录的稳定 ID 与显式引用字段，以及规范化对象引用和对象清单；忽略业务载荷、事件内容、配置值和对象内容。
- 任何孤立 Task/Attempt/Artifact、未知 Config Snapshot、失联 Quota/Sandbox、无效 Outbox aggregate、缺失对象或大小/SHA-256 不一致都会阻止继续；失败时保持入口关闭。
- 预检和一致性校验不会删除、修复或重放数据。演练输出应保存到受控审计系统，不要提交到仓库。
