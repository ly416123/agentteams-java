#!/usr/bin/env python3
"""固定 AgentTeams 公共签名、用户上下文和任务过程/产物契约。"""

from pathlib import Path
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = ROOT / "openapi" / "agentteams-public.yaml"


class SdkSignatureContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not SPEC_PATH.is_file():
            raise AssertionError(f"missing public OpenAPI spec: {SPEC_PATH}")
        with SPEC_PATH.open(encoding="utf-8") as stream:
            cls.spec = yaml.safe_load(stream)

    def resolve(self, value):
        if "$ref" not in value:
            return value
        _, path = value["$ref"].split("#/")
        resolved = self.spec
        for part in path.split("/"):
            resolved = resolved[part]
        return resolved

    def test_signature_scheme_and_required_headers_are_public(self):
        schemes = self.spec["components"]["securitySchemes"]
        self.assertIn("AgentTeamsSignature", schemes)
        self.assertEqual("apiKey", schemes["AgentTeamsSignature"]["type"])
        self.assertEqual("header", schemes["AgentTeamsSignature"]["in"])
        self.assertEqual("Authorization", schemes["AgentTeamsSignature"]["name"])

        parameters = self.spec["components"]["parameters"]
        expected = {
            "AtTimestamp": "X-AT-Timestamp",
            "AtNonce": "X-AT-Nonce",
            "AtOrganizationId": "X-AT-Organization-Id",
            "AtUserId": "X-AT-User-Id",
            "AtContentSha256": "X-AT-Content-SHA256",
            "AtSignature": "X-AT-Signature",
        }
        for component, header in expected.items():
            self.assertIn(component, parameters)
            parameter = parameters[component]
            self.assertEqual("header", parameter["in"])
            self.assertEqual(header, parameter["name"])
            self.assertTrue(parameter["required"])

    def test_public_operations_require_external_context_and_signature(self):
        required_refs = {
            "#/components/parameters/AtTimestamp",
            "#/components/parameters/AtNonce",
            "#/components/parameters/AtOrganizationId",
            "#/components/parameters/AtUserId",
            "#/components/parameters/AtContentSha256",
            "#/components/parameters/AtSignature",
        }
        for path, path_item in self.spec["paths"].items():
            for method, operation in path_item.items():
                if method not in {"get", "post", "put", "patch", "delete"}:
                    continue
                security = operation.get("security", self.spec["security"])
                security_names = set().union(*(entry.keys() for entry in security))
                self.assertIn("AgentTeamsSignature", security_names)
                refs = {
                    parameter.get("$ref")
                    for parameter in path_item.get("parameters", []) + operation.get("parameters", [])
                }
                self.assertTrue(required_refs <= refs, f"missing signed headers for {method} {path}")

    def test_task_process_result_and_artifact_contracts_are_exposed(self):
        paths = self.spec["paths"]
        self.assertIn("/api/v1/tasks/{taskId}/runs/{runId}/progress", paths)
        self.assertIn("/api/v1/tasks/{taskId}/runs/{runId}/process-events", paths)
        self.assertIn("/api/v1/tasks/{taskId}/runs/{runId}/result", paths)
        self.assertIn("/api/v1/tasks/{taskId}/runs/{runId}/artifacts", paths)
        schemas = self.spec["components"]["schemas"]
        for schema_name in ("TaskProgressSnapshot", "TaskProcessEvent", "TaskResultManifest", "TaskArtifactMetadata"):
            self.assertIn(schema_name, schemas)

    def test_all_public_errors_use_structured_api_error(self):
        responses = self.spec["components"]["responses"]
        for name in ("BadRequest", "Unauthorized", "Forbidden", "NotFound", "Conflict", "RateLimited", "ServerError"):
            self.assertIn(name, responses)
            content = responses[name]["content"]["application/json"]["schema"]
            self.assertEqual("#/components/schemas/ApiError", content["$ref"])


if __name__ == "__main__":
    unittest.main()
