#!/usr/bin/env python3
"""Validate the Console static deployment contract without requiring console/ source."""

import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
CHART = ROOT / "deploy/helm/agentteams-java"


class ValidationError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise ValidationError(message)


def text(path):
    require(path.exists(), f"缺少文件：{path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8")


def validate_static_contracts():
    dockerfile = text(ROOT / "deploy/docker/console.Dockerfile")
    nginx = text(ROOT / "deploy/docker/console-nginx.conf")
    values_path = ROOT / "deploy/helm/agentteams-java/values.yaml"
    values = yaml.safe_load(text(values_path))
    schema = json.loads(text(ROOT / "deploy/helm/agentteams-java/values.schema.json"))
    console_template = text(ROOT / "deploy/helm/agentteams-java/templates/console.yaml")
    ingress_template = text(ROOT / "deploy/helm/agentteams-java/templates/ingress.yaml")

    for required in (
        "FROM node:",
        "AS build",
        "COPY console/package*.json",
        "RUN npm ci",
        "RUN npm run build",
        "FROM nginxinc/nginx-unprivileged:",
        "COPY --from=build /workspace/console/dist /usr/share/nginx/html",
        "USER 101",
    ):
        require(required in dockerfile, f"Console Dockerfile 缺少：{required}")
    require("listen 8080" in nginx, "Nginx 必须监听非特权端口 8080")
    require("try_files $uri $uri/ /index.html;" in nginx, "Nginx 缺少 SPA history fallback")
    # Ingress sends API traffic directly to the backend, while the Kind/L5
    # Console NodePort is also a supported browser entry. Keep both paths
    # equivalent: the direct Console path must proxy API requests internally.
    for route in (
        "location ^~ /api/v1/conversations",
        "location ^~ /api/v1/manager",
        "location ^~ /api/",
        "proxy_pass http://agentteams-agentteams-java-manager:8080;",
        "proxy_pass http://agentteams-agentteams-java-control-plane:8080;",
    ):
        require(route in nginx, f"Console Nginx 缺少直连 NodePort API 路由：{route}")

    console = values.get("console")
    require(isinstance(console, dict), "values.yaml 缺少 console 对象")
    require(console.get("enabled") is False, "Console 默认必须关闭")
    require(isinstance(console.get("image"), str) and console["image"], "Console image 必须非空")
    require(set(console.get("config", {})) == {"apiBasePath", "oidcIssuer", "oidcClientId"},
            "Console config 只能包含公开 API/OIDC 字段")

    console_schema = schema["properties"].get("console")
    require(console_schema and console_schema.get("additionalProperties") is False,
            "values.schema.json 必须严格约束 console")
    config_schema = console_schema["properties"].get("config")
    require(config_schema and config_schema.get("additionalProperties") is False,
            "Console config schema 不得接受未知字段")
    require(set(config_schema["properties"]) == {"apiBasePath", "oidcIssuer", "oidcClientId"},
            "Console config schema 存在非公开字段")

    for required in (
        "if .Values.console.enabled",
        "kind: ConfigMap",
        "kind: Deployment",
        "kind: Service",
        "window.__AGENTTEAMS_CONFIG__",
        "containerSecurityContext",
        "podSecurityContext",
        "automountServiceAccountToken: false",
        "mountPath: /usr/share/nginx/html/config.js",
        "subPath: config.js",
        "port: 8080",
    ):
        require(required in console_template, f"Console Helm template 缺少：{required}")
    require("secretKeyRef" not in console_template and "secretName" not in console_template,
            "Console 静态工作负载不得引用 Secret")
    require("path: /api" in ingress_template and "path: /" in ingress_template,
            "Helm Ingress 缺少 API 或 Console 根路径")
    require("-console" in ingress_template and "-control-plane" in ingress_template,
            "Helm Ingress 后端服务不完整")

    kind_ingress = list(yaml.safe_load_all(text(ROOT / "deploy/kind-ingress.yaml")))[0]
    rule = next(rule for rule in kind_ingress["spec"]["rules"] if rule["host"] == "api.agentteams.localhost")
    paths = rule["http"]["paths"]
    require(paths[0]["path"] == "/api", "Kind Ingress 必须优先声明 /api")
    require(paths[0]["backend"]["service"]["name"] == "agentteams-agentteams-java-control-plane",
            "Kind /api 必须路由到 Control Plane")
    root_path = next(path for path in paths if path["path"] == "/")
    require(root_path["backend"]["service"]["name"] == "agentteams-agentteams-java-console",
            "Kind / 必须路由到 Console")


def validate_secret_hygiene():
    gitignore = text(ROOT / ".gitignore")
    require(".env*" in gitignore, ".gitignore 必须排除 .env* 文件")
    tracked = subprocess.run(
        ["git", "ls-files"], cwd=ROOT, capture_output=True, text=True, check=True
    ).stdout.splitlines()
    tracked_env = [
        path for path in tracked
        if Path(path).name.startswith(".env") and Path(path).name != ".env.example"
    ]
    require(not tracked_env, f"不得提交环境文件：{', '.join(tracked_env)}")

    console_template = text(ROOT / "deploy/helm/agentteams-java/templates/console.yaml")
    secret_patterns = r"(?i)(secretKeyRef|secretName|password|api[_-]?key|private[_-]?key)"
    require(not re.search(secret_patterns, console_template),
            "Console 静态资源模板包含疑似 Secret 字段")


def rendered_documents(*args):
    helm = shutil.which("helm")
    if helm is None:
        print("CONSOLE_HELM_RENDER_SKIPPED: helm 不可用，未执行渲染对象检查。")
        return None
    result = subprocess.run(
        [helm, "template", "agentteams", str(CHART), "--namespace", "agentteams", *args],
        capture_output=True,
        text=True,
    )
    require(result.returncode == 0, f"Helm template 失败：{result.stderr.strip()}")
    return [document for document in yaml.safe_load_all(result.stdout) if document]


def validate_rendered_contracts():
    disabled = rendered_documents()
    if disabled is None:
        return
    disabled_console = [
        document for document in disabled
        if document.get("metadata", {}).get("name", "").endswith("-console")
    ]
    require(not disabled_console, "console.enabled=false 时不应渲染 Console 资源")

    enabled = rendered_documents(
        "--set", "console.enabled=true",
        "--set", "ingress.enabled=true",
        "--set", "ingress.host=api.agentteams.localhost",
    )
    if enabled is None:
        return
    console_documents = [
        document for document in enabled
        if document.get("metadata", {}).get("name", "").endswith("-console")
    ]
    require({document["kind"] for document in console_documents} == {"ConfigMap", "Deployment", "Service"},
            "启用 Console 时必须渲染 ConfigMap、Deployment、Service")
    require(not any("secret" in json.dumps(document).lower() for document in console_documents),
            "Console 渲染对象不得包含 Secret")

    deployment = next(document for document in console_documents if document["kind"] == "Deployment")
    pod = deployment["spec"]["template"]
    container = pod["spec"]["containers"][0]
    require(pod["spec"]["securityContext"]["runAsNonRoot"], "Console Pod 必须 runAsNonRoot")
    require(pod["spec"]["automountServiceAccountToken"] is False,
            "Console Pod 不得自动挂载 ServiceAccount token")
    require(container["securityContext"]["readOnlyRootFilesystem"], "Console 容器必须只读根文件系统")
    require(container["ports"] == [{"name": "http", "containerPort": 8080}],
            "Console 容器必须暴露命名 8080 端口")
    require(any(mount.get("subPath") == "config.js" for mount in container["volumeMounts"]),
            "Console 必须从 ConfigMap 挂载 runtime config.js")

    ingress = next(document for document in enabled if document.get("kind") == "Ingress")
    paths = ingress["spec"]["rules"][0]["http"]["paths"]
    api = next(path for path in paths if path["path"] == "/api")
    root = next(path for path in paths if path["path"] == "/")
    require(api["backend"]["service"]["name"].endswith("-control-plane"),
            "Helm /api 必须路由到 Control Plane")
    require(root["backend"]["service"]["name"].endswith("-console"),
            "Helm / 必须路由到 Console")


def validate_source_status():
    console_dir = ROOT / "console"
    if not console_dir.exists():
        print("CONSOLE_SOURCE_SKIPPED: console/ 当前分支不存在，仅完成静态部署与 manifest 契约验证。")
        return
    require((console_dir / "package.json").exists(), "console/ 存在但缺少 package.json")
    print("CONSOLE_SOURCE_PRESENT: console/ 存在；npm test/build/lint 由 CI 执行。")


def main():
    try:
        validate_static_contracts()
        validate_secret_hygiene()
        validate_rendered_contracts()
        validate_source_status()
    except (ValidationError, KeyError, TypeError, yaml.YAMLError) as error:
        print(f"CONSOLE_MANIFEST_VALIDATION_FAILED: {error}", file=sys.stderr)
        return 1
    print("CONSOLE_MANIFEST_VALIDATION_OK: Docker/Nginx、Helm、Ingress 和 Secret 边界契约通过。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
