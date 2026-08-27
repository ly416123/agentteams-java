#!/usr/bin/env python3
"""Fail-closed health and error-budget gate for a signed release promotion."""

from __future__ import annotations

import argparse
import json
import math
import sys
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


COMPONENTS = ("control-plane", "gateway", "operator")


class PromotionGateError(ValueError):
    """Raised when promotion evidence is missing or violates policy."""


def _number(metrics: dict[str, Any], key: str) -> float:
    value = metrics.get(key)
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise PromotionGateError(f"{key} is missing or not a finite number")
    if value < 0:
        raise PromotionGateError(f"{key} must be non-negative")
    return float(value)


def evaluate_metrics(metrics: Any, policy: Any) -> list[str]:
    """Return stable breach messages; missing evidence is itself a breach."""
    if not isinstance(metrics, dict):
        return ["metrics must be a JSON object"]
    if not isinstance(policy, dict) or policy.get("schema_version") != 1:
        return ["policy schema_version must be 1"]

    breaches: list[str] = []
    try:
        error_rate = _number(metrics, "error_rate")
        max_error_rate = _number(policy, "max_error_rate")
        if error_rate > max_error_rate:
            breaches.append(f"error_rate exceeds {max_error_rate:g}")
    except PromotionGateError as exc:
        breaches.append(str(exc))

    try:
        latency = _number(metrics, "p95_latency_seconds")
        max_latency = _number(policy, "max_p95_latency_seconds")
        if latency > max_latency:
            breaches.append(f"p95_latency_seconds exceeds {max_latency:g}")
    except PromotionGateError as exc:
        breaches.append(str(exc))

    try:
        backlog = _number(metrics, "outbox_backlog")
        max_backlog = _number(policy, "max_outbox_backlog")
        if backlog > max_backlog:
            breaches.append(f"outbox_backlog exceeds {max_backlog:g}")
    except PromotionGateError as exc:
        breaches.append(str(exc))

    ready = metrics.get("ready_replicas")
    minimum = policy.get("min_ready_replicas")
    if not isinstance(ready, dict) or not isinstance(minimum, dict):
        breaches.append("ready_replicas evidence is missing")
    else:
        for component in COMPONENTS:
            value = ready.get(component)
            expected = minimum.get(component)
            if isinstance(value, bool) or not isinstance(value, int) or value < 0:
                breaches.append(f"ready_replicas.{component} is missing or invalid")
            elif isinstance(expected, bool) or not isinstance(expected, int) or expected < 1:
                breaches.append(f"min_ready_replicas.{component} is missing or invalid")
            elif value < expected:
                breaches.append(f"ready_replicas.{component} below {expected}")
    return breaches


def _prometheus_value(payload: Any, metric: str) -> float:
    if not isinstance(payload, dict) or payload.get("status") != "success":
        raise PromotionGateError(f"Prometheus query failed for {metric}")
    data = payload.get("data")
    results = data.get("result") if isinstance(data, dict) else None
    if not isinstance(results, list) or len(results) != 1:
        raise PromotionGateError(f"Prometheus query returned no unique value for {metric}")
    value = results[0].get("value")
    if not isinstance(value, list) or len(value) != 2:
        raise PromotionGateError(f"Prometheus query returned malformed value for {metric}")
    try:
        number = float(value[1])
    except (TypeError, ValueError) as exc:
        raise PromotionGateError(f"Prometheus query returned non-numeric value for {metric}") from exc
    if not math.isfinite(number) or number < 0:
        raise PromotionGateError(f"Prometheus query returned invalid value for {metric}")
    return number


def query_prometheus(base_url: str, policy: dict[str, Any], namespace: str,
                     deployments: dict[str, str], timeout: float = 10.0) -> dict[str, Any]:
    window = policy.get("window")
    if not isinstance(window, str) or not window.strip():
        raise PromotionGateError("policy window is missing")
    queries = policy.get("prometheus_queries")
    if not isinstance(queries, dict):
        raise PromotionGateError("policy prometheus_queries is missing")
    metrics: dict[str, Any] = {"ready_replicas": {}}
    for metric in ("error_rate", "p95_latency_seconds", "outbox_backlog"):
        query = queries.get(metric)
        if not isinstance(query, str) or not query.strip():
            raise PromotionGateError(f"policy query is missing for {metric}")
        metrics[metric] = _query(base_url, query.replace("{window}", window), metric, timeout)
    ready_query = queries.get("ready_replicas")
    if not isinstance(ready_query, str) or not ready_query.strip():
        raise PromotionGateError("policy query is missing for ready_replicas")
    for component in COMPONENTS:
        deployment = deployments.get(component)
        if not isinstance(deployment, str) or not deployment:
            raise PromotionGateError(f"deployment is missing for {component}")
        query = ready_query.replace("{namespace}", namespace).replace("{deployment}", deployment)
        query = query.replace("{window}", window)
        value = _query(base_url, query, f"ready_replicas.{component}", timeout)
        if not value.is_integer():
            raise PromotionGateError(f"Prometheus query returned fractional value for ready_replicas.{component}")
        metrics["ready_replicas"][component] = int(value)
    return metrics


def _query(base_url: str, query: str, metric: str, timeout: float) -> float:
    url = base_url.rstrip("/") + "/api/v1/query?" + urllib.parse.urlencode({"query": query})
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = json.load(response)
    except Exception as exc:  # noqa: BLE001 - the gate must convert all query failures to a stable error.
        raise PromotionGateError(f"Prometheus request failed for {metric}") from exc
    return _prometheus_value(payload, metric)


def _load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise PromotionGateError(f"cannot read JSON evidence: {path}") from exc


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--policy", required=True, type=Path)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--metrics-file", type=Path)
    source.add_argument("--prometheus-url")
    parser.add_argument("--namespace", default="agentteams")
    parser.add_argument("--deployment-prefix", default="agentteams-agentteams-java")
    args = parser.parse_args()

    try:
        policy = _load_json(args.policy)
        if not isinstance(policy, dict) or policy.get("schema_version") != 1:
            raise PromotionGateError("policy schema_version must be 1")
        if args.metrics_file:
            metrics = _load_json(args.metrics_file)
        else:
            prefix = args.deployment_prefix
            deployments = {component: f"{prefix}-{component}" for component in COMPONENTS}
            metrics = query_prometheus(args.prometheus_url, policy, args.namespace, deployments)
        breaches = evaluate_metrics(metrics, policy)
        if breaches:
            raise PromotionGateError("; ".join(breaches))
    except (PromotionGateError, KeyError, TypeError, ValueError) as exc:
        print(f"PROMOTION_GATE_FAIL: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
    print("PROMOTION_GATE_OK")


if __name__ == "__main__":
    main()
