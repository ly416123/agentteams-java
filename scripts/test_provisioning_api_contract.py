#!/usr/bin/env python3
"""固定 Provisioning API 的路由、幂等性、角色和错误契约。"""

from pathlib import Path
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = ROOT / "openapi" / "agentteams-provisioning.yaml"


class ProvisioningApiContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not SPEC_PATH.is_file():
            raise AssertionError(f"missing provisioning OpenAPI spec: {SPEC_PATH}")
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

    def test_required_provisioning_routes_exist(self):
        expected = {
            "/api/v1/provisioning/connection": {"get"},
            "/api/v1/provisioning/users": {"post"},
            "/api/v1/provisioning/users/{externalUserId}": {"put"},
            "/api/v1/provisioning/users/{externalUserId}/disable": {"post"},
            "/api/v1/provisioning/users/{externalUserId}/memberships": {"get"},
        }
        for path, methods in expected.items():
            self.assertIn(path, self.spec["paths"])
            self.assertEqual(methods, {method for method in self.spec["paths"][path] if method in {"get", "post", "put"}})

    def test_mutating_routes_require_idempotency_key(self):
        for path, method in (
            ("/api/v1/provisioning/users", "post"),
            ("/api/v1/provisioning/users/{externalUserId}", "put"),
            ("/api/v1/provisioning/users/{externalUserId}/disable", "post"),
        ):
            operation = self.spec["paths"][path][method]
            names = {parameter["$ref"].rsplit("/", 1)[-1] for parameter in operation["parameters"]}
            self.assertIn("IdempotencyKey", names)

    def test_provisioning_uses_signed_external_context_and_forbids_owner(self):
        serialized = SPEC_PATH.read_text(encoding="utf-8")
        self.assertIn("AgentTeamsSignature", self.spec["components"]["securitySchemes"])
        self.assertNotIn("OWNER", serialized)
        schemas = self.spec["components"]["schemas"]
        self.assertNotIn("role", schemas["ProvisioningUserRequest"].get("properties", {}))
        self.assertIn("externalOrganizationId", schemas["ProvisioningUserRequest"]["required"])
        self.assertIn("externalUserId", schemas["ProvisioningUserRequest"]["required"])

    def test_all_routes_expose_structured_errors(self):
        for path, path_item in self.spec["paths"].items():
            for method, operation in path_item.items():
                if method not in {"get", "post", "put"}:
                    continue
                for status in ("400", "401", "403", "409", "500"):
                    self.assertIn(status, operation["responses"], f"missing {status} for {method} {path}")
                    response = self.resolve(operation["responses"][status])
                    schema = response["content"]["application/json"]["schema"]
                    self.assertEqual("#/components/schemas/ApiError", schema["$ref"])


if __name__ == "__main__":
    unittest.main()
