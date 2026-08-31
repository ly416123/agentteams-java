#!/usr/bin/env python3
"""Contract tests for the versioned public OpenAPI baseline."""

from pathlib import Path
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = ROOT / "openapi" / "agentteams-public.yaml"


class PublicOpenApiContractTest(unittest.TestCase):
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

    def test_spec_is_openapi_31_and_versioned(self):
        self.assertEqual("3.1.0", self.spec["openapi"])
        self.assertEqual("AgentTeams Public API", self.spec["info"]["title"])
        self.assertRegex(self.spec["info"]["version"], r"^v\d+\.\d+$")

    def test_core_resource_paths_have_operation_ids(self):
        expected = {
            "/api/v1/projects": {"get", "post"},
            "/api/v1/tasks": {"get", "post"},
            "/api/v1/tasks/{taskId}": {"get"},
            "/api/v1/tasks/{taskId}/cancel": {"post"},
        }
        for path, methods in expected.items():
            self.assertIn(path, self.spec["paths"])
            for method in methods:
                operation = self.spec["paths"][path][method]
                self.assertRegex(operation["operationId"], r"^[a-z][A-Za-z0-9]+$")
                security = operation.get("security", self.spec["security"])
                self.assertIn("BearerAuth", security[0])

    def test_write_operations_require_idempotency_and_errors_are_stable(self):
        for path, method in (
            ("/api/v1/projects", "post"),
            ("/api/v1/tasks", "post"),
            ("/api/v1/tasks/{taskId}/cancel", "post"),
        ):
            operation = self.spec["paths"][path][method]
            headers = {
                self.resolve(parameter)["name"] for parameter in operation["parameters"]
            }
            self.assertIn("Idempotency-Key", headers)
            for status in ("400", "401", "403", "409", "429", "500"):
                self.assertIn(status, operation["responses"])

    def test_cursor_page_and_error_schema_are_public_and_non_sensitive(self):
        schemas = self.spec["components"]["schemas"]
        self.assertIn("CursorPage", schemas)
        self.assertIn("ApiError", schemas)
        self.assertEqual(
            {"code", "message", "correlationId", "details"},
            set(schemas["ApiError"]["properties"]),
        )
        serialized = SPEC_PATH.read_text(encoding="utf-8").lower()
        for forbidden in ("client_secret", "api_key", "private_key", "password"):
            self.assertNotIn(forbidden, serialized)

    def test_internal_and_matrix_endpoints_are_not_public(self):
        serialized_paths = "\n".join(self.spec["paths"])
        self.assertNotIn("/internal", serialized_paths)
        self.assertNotIn("matrix", serialized_paths.lower())


if __name__ == "__main__":
    unittest.main()
