# OIDC JWT 验证实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `subagent-driven-development` 或 `executing-plans` 逐任务实现此计划。步骤使用复选框（`- [ ]`）跟踪进度。

**目标：** 为启用的 Control Plane HTTP API 提供可配置的 OIDC JWT 签名、issuer、audience 和权限校验，并将身份映射到现有租户/项目/团队授权边界。

**架构：** 保留现有 `ApiAuthenticationFilter` 和 `IdentityTokenValidator` 边界，新增基于 Spring Security Nimbus JOSE 的验证器。验证器使用显式 JWKS URI，校验 issuer/audience，并从固定 claim 映射 subject、tenant、project、team 和 permissions；缺少配置或验证失败都拒绝请求。

**技术栈：** Java 17、Spring Boot 3.4、Spring Security OAuth2 Resource Server、Nimbus JOSE、JUnit 5。

## 任务 1：验证器行为测试

- [x] 测试有效 RSA JWT 映射身份和权限。
- [x] 测试错误 issuer、audience、签名和缺少 scope claim 被拒绝。
- [x] 测试 claim 映射配置可改变租户、项目、团队和权限 claim 名称。

## 任务 2：实现 OIDC 验证器

- [x] 增加 OAuth2 Resource Server 依赖。
- [x] 新增配置属性和 `NimbusJwtDecoder` 验证器，启用 issuer/audience 校验。
- [x] 接入 `ControlPlaneConfiguration`，仅在 API 认证开启且 OIDC 配置有效时创建验证器。

## 任务 3：部署配置和文档

- [x] 增加 application/Helm 的 issuer、JWKS、audience 和 claim 映射配置。
- [x] API 启用但 OIDC 配置缺失时启动失败，避免无认证运行。
- [x] 增加配置示例、验证命令和安全边界说明。

## 任务 4：验证与交付

- [x] 运行安全单测、Maven 全量回归、Helm lint/template 和敏感信息扫描。
- [x] 更新阶段计划，提交并推送功能分支。
