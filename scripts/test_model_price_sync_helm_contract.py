#!/usr/bin/env python3
import shutil
import subprocess
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
CHART = ROOT / "deploy/helm/agentteams-java"
HELM = shutil.which("helm")


class ModelPriceSyncHelmContractTest(unittest.TestCase):
    def test_poll_interval_is_decimal_string_for_spring_long_binding(self):
        values = yaml.safe_load((CHART / "values.yaml").read_text(encoding="utf-8"))
        self.assertIsInstance(
            values["controlPlane"]["usagePriceSync"]["pollIntervalMs"], str
        )
        self.assertEqual(
            values["controlPlane"]["usagePriceSync"]["pollIntervalMs"], "3600000"
        )

    @unittest.skipUnless(HELM, "helm is unavailable")
    def test_rendered_poll_interval_is_not_scientific_notation(self):
        result = subprocess.run(
            [HELM, "template", "agentteams", str(CHART), "--namespace", "agentteams"],
            check=True,
            capture_output=True,
            text=True,
        )
        documents = [doc for doc in yaml.safe_load_all(result.stdout) if doc]
        deployment = next(
            doc
            for doc in documents
            if doc.get("kind") == "Deployment"
            and doc.get("metadata", {}).get("name", "").endswith("-control-plane")
        )
        env = {
            item["name"]: item["value"]
            for item in deployment["spec"]["template"]["spec"]["containers"][0]["env"]
            if "value" in item
        }
        self.assertEqual(env["AGENTTEAMS_USAGE_PRICE_SYNC_POLL_INTERVAL_MS"], "3600000")


if __name__ == "__main__":
    unittest.main()
