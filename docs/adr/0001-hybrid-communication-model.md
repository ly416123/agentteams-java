---
status: accepted
date: 2026-09-23
---

# 采用混合通信模式

平台采用混合通信模式：Control Plane 继续拥有任务、Attempt、权限、审计和执行状态的事实源；Conversation 与 Task Event 负责持久化协作上下文和结构化过程事件；Console、SDK、Matrix 作为共享同一业务语义的展示与操作渠道。Matrix 可以提供类似 AgentTeams 的人机协作房间，但不升级为 Worker 内部通信、调度、租约、文件传输或整个系统的唯一总线。

## Considered Options

- **Matrix 作为整个系统唯一通信总线：** 不采用。Matrix 适合人机协作和事件可见性，但不天然提供业务事务、聚合顺序、租约、高频运行时消息和大文件传输所需的语义。
- **仅保留 REST / SDK / Console，Matrix 只做通知：** 不采用。这样会削弱房间协作、人工介入和 Manager / Worker 过程可见性。
- **混合通信模式：** 采用。让 Control Plane 保持业务权威，让各渠道通过类型化命令和持久化事件接入。

## Consequences

- 所有渠道必须通过统一的授权、幂等、版本和审计边界改变业务状态。
- Conversation 和 Task Event 必须支持游标、重放和渠道投影，Console、SDK、Matrix 不能各自维护一套任务状态。
- Matrix 房间绑定到 Project、Team、Task 或 Conversation；房间成员关系不能替代平台 RBAC。
- Worker 与 Gateway 的执行链路继续使用面向可靠运行时的专用协议；文件和 Artifact 继续使用对象存储与引用。
