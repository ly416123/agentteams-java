package io.agentteams.controlplane.security;

/** Stable service-layer actions shared by HTTP, tools and asynchronous consumers. */
public enum ResourceAction {
    PROJECT_READ,
    PROJECT_MEMBER_INVITE,
    PROJECT_MEMBER_DISABLE,
    PROJECT_MEMBER_ENABLE,
    PROJECT_MEMBER_ROLE_CHANGE,
    PROJECT_OWNER_TRANSFER,
    TEAM_READ,
    TEAM_WRITE,
    TASK_READ,
    TASK_CREATE,
    TASK_CANCEL,
    WORKER_OPERATE
}
