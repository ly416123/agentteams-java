import base64
import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch


MODULE_PATH = Path(__file__).with_name("chatgpt_plan_token_refresh.py")
SPEC = importlib.util.spec_from_file_location("chatgpt_plan_token_refresh", MODULE_PATH)
refresh = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(refresh)


class ChatGptPlanTokenRefreshTest(unittest.TestCase):
    def test_refreshes_only_near_expiry_after_server_earliest_time(self):
        session = {"expires-at": "5000", "earliest-refresh-at": "4200"}
        self.assertFalse(refresh.refresh_due(session, 4000))
        self.assertFalse(refresh.refresh_due(session, 4100))
        self.assertTrue(refresh.refresh_due(session, 4200))

    def test_rotates_access_and_refresh_tokens_together(self):
        current = {"client-id": "issued-id", "access-token": "old-a", "refresh-token": "old-r",
                   "scopes": "openid chatgpt.tokens.use.direct"}
        result = refresh.rotated_session(current, {
            "access_token": "new-a", "refresh_token": "new-r", "expires_in": 3600,
            "scope": "openid chatgpt.tokens.use.direct", "earliest_refresh_at": 1900,
        }, now=1000)
        self.assertEqual(result["access-token"], "new-a")
        self.assertEqual(result["refresh-token"], "new-r")
        self.assertEqual(result["expires-at"], "4600")
        self.assertEqual(result["earliest-refresh-at"], "1900")
        self.assertEqual(current["refresh-token"], "old-r")

    def test_rejects_refresh_response_without_plan_scope_or_rotated_refresh_token(self):
        base = {"client-id": "issued-id", "refresh-token": "old", "scopes": "openid"}
        with self.assertRaisesRegex(RuntimeError, "lacks the ChatGPT plan scope"):
            refresh.rotated_session(base, {"access_token": "new", "refresh_token": "r2",
                                           "expires_in": 3600}, now=100)
        with self.assertRaisesRegex(RuntimeError, "incomplete"):
            refresh.rotated_session(base, {"access_token": "new", "expires_in": 3600,
                                           "scope": "chatgpt.tokens.use.direct"}, now=100)

    def test_decodes_session_keys_without_printing_values(self):
        secret = {"data": {"access-token": base64.b64encode(b"access").decode(),
                           "refresh-token": base64.b64encode(b"refresh").decode()}}
        self.assertEqual(refresh.decode_session(secret), {"access-token": "access", "refresh-token": "refresh"})

    def test_retries_conflicted_secret_patch_only_when_session_was_not_replaced(self):
        original = {"metadata": {"resourceVersion": "1"}}
        latest = {"metadata": {"resourceVersion": "2"}, "data": {
            "refresh-token": base64.b64encode(b"old-refresh").decode(),
            "access-token": base64.b64encode(b"old-access").decode(),
        }}
        updated = {"refresh-token": "new-refresh", "access-token": "new-access"}
        with patch.object(refresh, "kubernetes_request", side_effect=[
                RuntimeError("Kubernetes API HTTP 409"), latest, {}]) as request:
            refresh.patch_session("session", original, "old-refresh", updated)
        self.assertEqual(request.call_count, 3)
        self.assertEqual(request.call_args_list[-1].args[2]["metadata"]["resourceVersion"], "2")

    def test_does_not_overwrite_a_new_session_after_patch_conflict(self):
        latest = {"metadata": {"resourceVersion": "2"}, "data": {
            "refresh-token": base64.b64encode(b"user-reauthorized").decode(),
        }}
        with patch.object(refresh, "kubernetes_request", side_effect=[RuntimeError("HTTP 409"), latest]):
            with self.assertRaisesRegex(RuntimeError, "OAuth session changed while refreshing"):
                refresh.patch_session("session", {"metadata": {"resourceVersion": "1"}},
                                      "old-refresh", {"refresh-token": "rotated"})


if __name__ == "__main__":
    unittest.main()
