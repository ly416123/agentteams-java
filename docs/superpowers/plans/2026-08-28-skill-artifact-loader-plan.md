# 批次 C Skill Artifact Loader 实现计划

## 目标

让 Worker 对显式下发的 Skill 预签名地址执行受限下载、大小校验、SHA-256 校验和原子落盘，并将下载状态反映到资源级 ACK。

## 验收

- [x] 仅允许 HTTP/HTTPS artifactRef；
- [x] 限制最大包大小，拒绝超限响应；
- [x] 同时校验声明大小和 SHA-256，失败不留下半成品；
- [x] 使用不可由引用直接控制的本地文件名；
- [x] 下载成功后才返回资源 `APPLIED`，失败分类为 `DOWNLOAD_FAILED`；
- [x] 旧 manifest 无 artifactRef 时保持兼容；
- [x] Control Plane 从已发布 Skill 版本生成预签名 artifactRef；
- [x] Skill 包解压、扫描结果复核和真实运行时注册。

## 边界

本批只负责安全获取和完整性验证，不执行包内代码，也不把下载完成等同于 Skill 业务能力已经可用。后续运行时注册必须经过独立 Port 和沙箱策略。

## 本批补充：Control Plane 制品引用

已在 AgentSpec 的引用元数据与绑定中增加可选 `artifactRef`、`sizeBytes` 字段。
Skill Catalog 仅对已发布且上传状态为 `COMPLETED` 的版本调用
`SkillPackageStorageService` 生成 15 分钟短期下载地址，并使用已验证的包大小和
SHA-256 作为 Worker 完整性校验值。Deployment manifest 只在字段成对存在时下发，
MODEL、MCP 及无包元数据的旧 Skill 绑定保持原有格式。

## 本批补充：Worker 运行时物化与注册

Worker 在下载并校验归档后，将 ZIP、tar 或 gzip-tar 以受限临时目录解包，拒绝路径穿越、重复路径、
不支持的 tar 特殊条目、符号链接和超出展开大小的归档；随后要求根目录包含可由 AgentScope 解析的
`SKILL.md`，作为本地扫描结果复核。物化目录写入不可变 `RuntimeConfigSnapshot.skillDirectories`，
仅在配置激活时由 AgentScope 创建只读 `FileSystemSkillRepository`，并关闭默认工作区 Skill 与动态 Skill，
避免未显式下发的内容进入运行时。无 `artifactRef` 的旧 manifest 不产生 Skill 目录，也不改变原有路径。
