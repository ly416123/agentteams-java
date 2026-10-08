import unittest

from scripts.agentteams_eval import evaluate_observation, load_cases


class SourceCitationContractTest(unittest.TestCase):
    def test_positive_multi_agent_case_declares_citation_assertion(self):
        case = next(case for case in load_cases("eval/cases/core.yaml")
                    if case.case_id == "multi-agent-pdf-positive")
        self.assertIn("source_citation", [assertion.get("type") for assertion in case.assertions])

    def test_unstructured_source_citation_evidence_is_inconclusive(self):
        case = next(case for case in load_cases("eval/cases/core.yaml")
                    if case.case_id == "multi-agent-pdf-positive")
        assertion = next(assertion for assertion in case.assertions
                         if assertion.get("type") == "source_citation")
        result = evaluate_observation(
            type(case)("citation", "Citation", {}, [assertion], {}),
            {"run": {"id": "run-citation"}, "events": [],
             "result": {"summary": "Source: https://example.org/"}})[0]

        self.assertEqual("INCONCLUSIVE", result.status)
        self.assertIn("structured source citation evidence is unavailable", result.message)
        self.assertNotIn("example.org", str(result.as_dict()))


if __name__ == "__main__":
    unittest.main()
