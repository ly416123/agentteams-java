import base64
import importlib.util
import json
from pathlib import Path
import time
import unittest
from unittest.mock import patch

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa


MODULE_PATH = Path(__file__).with_name("chatgpt_plan_oauth.py")
SPEC = importlib.util.spec_from_file_location("chatgpt_plan_oauth", MODULE_PATH)
oauth = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(oauth)


def enc(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode().rstrip("=")


class ChatGptPlanOauthTest(unittest.TestCase):
    def test_verifies_id_token_signature_issuer_audience_expiry_and_nonce(self):
        private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        public = private.public_key().public_numbers()
        header = {"alg": "RS256", "kid": "test-key"}
        claims = {"iss": "https://auth.openai.com", "aud": "issued-client", "exp": int(time.time()) + 60,
                  "nonce": "expected-nonce", "sub": "account-subject"}
        signing_input = enc(json.dumps(header, separators=(",", ":")).encode()) + "." + enc(
            json.dumps(claims, separators=(",", ":")).encode())
        signature = private.sign(signing_input.encode(), padding.PKCS1v15(), hashes.SHA256())
        token = signing_input + "." + enc(signature)
        jwks = {"keys": [{"kid": "test-key", "kty": "RSA", "n": enc(public.n.to_bytes(256, "big")),
                           "e": enc(public.e.to_bytes(3, "big"))}]}
        with patch.object(oauth, "http_json", side_effect=[{"issuer": "https://auth.openai.com",
                                                              "jwks_uri": "https://auth.openai.com/keys"}, jwks]):
            verified = oauth.verify_id_token(token, "issued-client", "expected-nonce")
        self.assertEqual(verified["sub"], "account-subject")

    def test_rejects_invalid_nonce_even_for_validly_signed_token(self):
        private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        public = private.public_key().public_numbers()
        header = {"alg": "RS256", "kid": "test-key"}
        claims = {"iss": "https://auth.openai.com", "aud": "issued-client", "exp": int(time.time()) + 60,
                  "nonce": "wrong-nonce", "sub": "account-subject"}
        signing_input = enc(json.dumps(header, separators=(",", ":")).encode()) + "." + enc(
            json.dumps(claims, separators=(",", ":")).encode())
        token = signing_input + "." + enc(private.sign(signing_input.encode(), padding.PKCS1v15(), hashes.SHA256()))
        jwks = {"keys": [{"kid": "test-key", "kty": "RSA", "n": enc(public.n.to_bytes(256, "big")),
                           "e": enc(public.e.to_bytes(3, "big"))}]}
        with patch.object(oauth, "http_json", side_effect=[{"issuer": "https://auth.openai.com",
                                                              "jwks_uri": "https://auth.openai.com/keys"}, jwks]):
            with self.assertRaisesRegex(RuntimeError, "ID token validation failed"):
                oauth.verify_id_token(token, "issued-client", "expected-nonce")

    def test_secret_initialization_never_puts_credential_values_in_command_arguments(self):
        with patch.object(oauth, "run_kubectl", side_effect=[
                type("Result", (), {"returncode": 0, "stdout": "apiVersion: v1\nkind: Secret"})(),
                type("Result", (), {"returncode": 0, "stdout": ""})()]) as kubectl:
            oauth.apply_session_secret("l5", "agentteams", "session", {"access-token": "secret-value"})
        for call in kubectl.call_args_list:
            self.assertNotIn("secret-value", " ".join(call.args[0]))
        self.assertIn("access-token=secret-value", kubectl.call_args_list[0].args[1])

    def test_callback_timeout_is_reported_separately_from_state_mismatch(self):
        class Args:
            context = "l5"
            namespace = "agentteams"
            secret_name = "session"

        with patch.object(oauth, "host_id", return_value="urn:uuid:test"), \
                patch.object(oauth, "secret_data", return_value=None), \
                patch.object(oauth, "HTTPServer") as server_factory, \
                patch.object(oauth.webbrowser, "open"), \
                patch("builtins.print"):
            server_factory.return_value.handle_request.return_value = None
            with self.assertRaisesRegex(RuntimeError, "callback timed out"):
                oauth.authorize(Args())


if __name__ == "__main__":
    unittest.main()
