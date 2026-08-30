#!/usr/bin/env python3
"""Contract tests for the authenticated QwenPaw DeepSeek smoke script."""

import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "smoke-kind-qwenpaw-deepseek.sh"


class DeepSeekSmokeScriptContractTest(unittest.TestCase):
    def test_optional_auth_arguments_are_safe_when_token_is_unset(self):
        source = SCRIPT.read_text(encoding="utf-8")

        self.assertIn("curl_api()", source)
        self.assertIn("if (( ${#API_AUTH_ARGS[@]} > 0 )); then", source)
        self.assertIn('curl_api --fail --silent "${BASE_URL}/actuator/health"', source)
        self.assertNotIn('curl --fail --silent "${API_AUTH_ARGS[@]}"', source)

    def test_missing_token_bootstraps_local_keycloak_alice_token(self):
        source = SCRIPT.read_text(encoding="utf-8")

        self.assertIn('KEYCLOAK_PORT="${KIND_KEYCLOAK_LOCAL_PORT:-18082}"', source)
        self.assertIn('kubectl -n "${NAMESPACE}" get service/keycloak', source)
        self.assertIn('kubectl -n "${NAMESPACE}" port-forward service/keycloak', source)
        self.assertIn(
            '"${KEYCLOAK_URL}/realms/agentteams/.well-known/openid-configuration"',
            source,
        )
        self.assertIn("--data-urlencode 'grant_type=password'", source)
        self.assertIn("--data-urlencode 'client_id=agentteams-api'", source)
        self.assertIn('"username=${AGENTTEAMS_API_USERNAME:-alice}"', source)
        self.assertIn('"password=${AGENTTEAMS_API_PASSWORD:-alice-dev}"', source)
        self.assertIn('TOKEN="$(jq -er \'.access_token\' <<<"${token_response}")"', source)

    def test_explicit_token_has_priority_and_keycloak_forward_is_cleaned_up(self):
        source = SCRIPT.read_text(encoding="utf-8")

        explicit_token = source.index('if [[ -n "${AGENTTEAMS_API_TOKEN:-}" ]]')
        self.assertIn('port-forward service/keycloak', source)
        keycloak_forward = source.index('port-forward service/keycloak')
        self.assertLess(explicit_token, keycloak_forward)
        self.assertIn('KEYCLOAK_PID=""', source)
        self.assertIn('kill "${KEYCLOAK_PID}"', source)
        self.assertIn('wait "${KEYCLOAK_PID}"', source)
        self.assertIn('rm -f "${PORT_FORWARD_LOG}" "${KEYCLOAK_LOG}"', source)

    def test_smoke_does_not_print_token_or_complete_model_response(self):
        source = SCRIPT.read_text(encoding="utf-8")

        self.assertNotIn('echo "${TOKEN}"', source)
        self.assertNotIn('echo "${token_response}"', source)
        self.assertNotIn('echo "${TASK_JSON}"', source)
        self.assertNotIn('printf "%s\\n" "${TOKEN}"', source)


if __name__ == "__main__":
    unittest.main()
