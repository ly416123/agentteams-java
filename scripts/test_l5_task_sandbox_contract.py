#!/usr/bin/env python3
import re
import unittest
import uuid
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
EXAMPLES = {
    "isolated": ROOT / "deploy/examples/task-sandbox-isolated.yaml",
    "hardened": ROOT / "deploy/examples/task-sandbox-hardened.yaml",
}
EXPECTED = {
    "isolated": {
        "profile": "ISOLATED",
        "runtimeClassName": "gvisor",
        "taskId": "00000000-0000-0000-0000-000000000101",
        "attemptId": "00000000-0000-0000-0000-000000000201",
    },
    "hardened": {
        "profile": "HARDENED",
        "runtimeClassName": "kata-qemu",
        "taskId": "00000000-0000-0000-0000-000000000102",
        "attemptId": "00000000-0000-0000-0000-000000000202",
    },
}
EXPECTED_IMAGE = "ghcr.io/ly416123/agentteams-task-sandbox:latest"
ALLOWED_TOP_LEVEL_FIELDS = {"apiVersion", "kind", "metadata", "spec"}
ALLOWED_METADATA_FIELDS = {"name", "namespace"}
ALLOWED_SPEC_FIELDS = {
    "taskId",
    "attemptId",
    "idempotencyKey",
    "profile",
    "runtimeClassName",
    "image",
    "ttlSeconds",
    "template",
    "expiresAt",
    "terminationRequested",
    "terminationReason",
    "resources",
}
EXPECTED_SPEC_FIELDS = {
    "taskId",
    "attemptId",
    "idempotencyKey",
    "profile",
    "runtimeClassName",
    "image",
    "ttlSeconds",
    "template",
}
FORBIDDEN_FIELD_NAMES = {
    "hostpath",
    "hostnetwork",
    "hostpid",
    "hostipc",
    "serviceaccount",
    "serviceaccountname",
    "secret",
    "secretname",
    "secretref",
    "volumes",
    "volumemounts",
}
CREDENTIAL_PATTERN = re.compile(
    r"(?i)(?:password|passwd|secret|token|credential|private[_-]?key|api[_-]?key)"
)


def _mapping_keys(value):
    if isinstance(value, dict):
        for key, child in value.items():
            yield str(key).replace("-", "").replace("_", "").lower()
            yield from _mapping_keys(child)
    elif isinstance(value, list):
        for child in value:
            yield from _mapping_keys(child)


def _scalar_values(value):
    if isinstance(value, dict):
        for child in value.values():
            yield from _scalar_values(child)
    elif isinstance(value, list):
        for child in value:
            yield from _scalar_values(child)
    else:
        yield value


class L5TaskSandboxContractTest(unittest.TestCase):
    def load_example(self, name):
        path = EXAMPLES[name]
        self.assertTrue(path.is_file(), f"missing L5 example: {path}")
        documents = list(yaml.safe_load_all(path.read_text(encoding="utf-8")))
        self.assertEqual(1, len(documents), f"{path} must contain exactly one YAML document")
        self.assertIsInstance(documents[0], dict)
        return documents[0]

    def test_examples_use_the_task_sandbox_v1alpha1_resource(self):
        for name in EXAMPLES:
            with self.subTest(example=name):
                manifest = self.load_example(name)
                self.assertEqual(ALLOWED_TOP_LEVEL_FIELDS, set(manifest))
                self.assertEqual("agentteams.io/v1alpha1", manifest["apiVersion"])
                self.assertEqual("TaskSandbox", manifest["kind"])
                self.assertEqual(
                    {"name": f"task-sandbox-l5-{name}", "namespace": "agentteams"},
                    manifest["metadata"],
                )

    def test_profiles_map_to_their_configured_l5_runtime_classes(self):
        for name, expected in EXPECTED.items():
            with self.subTest(example=name):
                spec = self.load_example(name)["spec"]
                self.assertEqual(expected["profile"], spec["profile"])
                self.assertEqual(expected["runtimeClassName"], spec["runtimeClassName"])
                self.assertEqual(EXPECTED_IMAGE, spec["image"])
                self.assertEqual(f"sandbox:l5-{name}-example", spec["idempotencyKey"])
                self.assertEqual("python-untrusted", spec["template"])
                self.assertIs(type(spec["ttlSeconds"]), int)
                self.assertGreaterEqual(spec["ttlSeconds"], 60)
                self.assertLessEqual(spec["ttlSeconds"], 86400)

    def test_examples_use_fixed_non_production_uuid_fixtures(self):
        observed = set()
        for name, expected in EXPECTED.items():
            with self.subTest(example=name):
                spec = self.load_example(name)["spec"]
                self.assertEqual(expected["taskId"], spec["taskId"])
                self.assertEqual(expected["attemptId"], spec["attemptId"])
                for field in ("taskId", "attemptId"):
                    value = spec[field]
                    self.assertEqual(value, str(uuid.UUID(value)))
                    self.assertTrue(value.startswith("00000000-0000-0000-0000-"))
                    self.assertNotIn(value, observed)
                    observed.add(value)

    def test_examples_have_no_uncontrolled_host_or_credential_boundary(self):
        for name in EXAMPLES:
            with self.subTest(example=name):
                manifest = self.load_example(name)
                self.assertEqual(ALLOWED_METADATA_FIELDS, set(manifest["metadata"]))
                self.assertEqual(EXPECTED_SPEC_FIELDS, set(manifest["spec"]))
                forbidden_fields = FORBIDDEN_FIELD_NAMES.intersection(set(_mapping_keys(manifest)))
                self.assertFalse(forbidden_fields, f"forbidden fields in {name}: {forbidden_fields}")
                for value in _scalar_values(manifest):
                    if isinstance(value, str):
                        self.assertIsNone(CREDENTIAL_PATTERN.search(value))


if __name__ == "__main__":
    unittest.main()
