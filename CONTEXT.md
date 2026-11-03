# AgentTeams 身份与授权

本上下文定义 AgentTeams 中登录身份、业务用户映射和组织授权的边界。

## Language

**Keycloak 账号**：Keycloak Realm 中用于登录的账号，以 Realm 用户 ID 作为 OIDC `sub`。账号凭据由 Keycloak 管理。
_Avoid_：内部用户、外部用户

**内部用户**：AgentTeams 管理域中的用户记录，以登录主体 `sub` 标识，并承载平台内显示名称和状态。
_Avoid_：Keycloak 账号

**外部身份**：某个集成下的外部组织 ID 与外部用户 ID 到内部用户的映射。它表达业务系统身份关联，本身不授予 Console 登录能力。
_Avoid_：登录账号、组织成员

**组织成员关系**：内部用户在一个 AgentTeams 组织中的角色关联。组织角色作用于该组织，不等同于 Keycloak Realm 角色。
_Avoid_：用户身份映射、项目成员关系
