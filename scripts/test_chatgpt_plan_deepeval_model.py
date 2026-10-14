import json
import unittest
from unittest.mock import patch

from eval.chatgpt_plan_deepeval import ChatGptPlanDeepEvalModel, _completed_response


class ChatGptPlanDeepEvalModelTest(unittest.TestCase):
    def test_uses_responses_api_json_mode_and_extracts_text(self):
        model = ChatGptPlanDeepEvalModel(model="gpt-5.6-luna", access_token="test-token",
                                         endpoint="https://example.test/v1/responses")
        response = type("Response", (), {
            "__enter__": lambda self: self,
            "__exit__": lambda self, *_: None,
            "readline": lambda self: next(lines),
        })()
        lines = iter([('data: ' + json.dumps({"type": "response.completed", "response": {
            "output_text": '{"score": 0.9}'}}) + '\n').encode(), b""])

        with patch("eval.chatgpt_plan_deepeval.urlopen", return_value=response) as open_url:
            result = model.generate("judge this")

        request = open_url.call_args.args[0]
        body = json.loads(request.data)
        self.assertEqual('{"score": 0.9}', result)
        self.assertEqual("gpt-5.6-luna", body["model"])
        self.assertEqual("judge this", body["input"][0]["content"][0]["text"])
        self.assertFalse(body["store"])
        self.assertTrue(body["stream"])
        self.assertEqual({"type": "json_object"}, body["text"]["format"])
        self.assertEqual({"effort": "low"}, body["reasoning"])
        self.assertEqual(2048, body["max_output_tokens"])
        self.assertEqual("Bearer test-token", request.get_header("Authorization"))

    def test_requires_access_token_without_printing_credentials(self):
        with self.assertRaisesRegex(RuntimeError, "CHATGPT_OAUTH_ACCESS_TOKEN is required"):
            ChatGptPlanDeepEvalModel(access_token="")

    def test_reassembles_response_text_stream_deltas(self):
        stream = "\n".join([
            'data: ' + json.dumps({"type": "response.output_text.delta", "delta": '{"score"'}),
            'data: ' + json.dumps({"type": "response.output_text.delta", "delta": ': 1}'}),
            'data: ' + json.dumps({"type": "response.completed", "response": {"output": []}}),
        ])

        self.assertEqual('{"score": 1}', _completed_response(stream)["output_text"])

    def test_incomplete_stream_error_reports_event_types_without_response_content(self):
        stream = "\n".join([
            'data: ' + json.dumps({"type": "response.created", "response": {"id": "resp-secret"}}),
            'data: ' + json.dumps({"type": "response.output_text.delta", "delta": '{"score": 1}'}),
        ])

        with self.assertRaisesRegex(RuntimeError, "response.created.*response.output_text.delta") as raised:
            _completed_response(stream)

        self.assertNotIn("resp-secret", str(raised.exception))
        self.assertNotIn('{"score": 1}', str(raised.exception))

    def test_incomplete_response_reports_only_whitelisted_reason(self):
        stream = 'data: ' + json.dumps({"type": "response.incomplete", "response": {
            "incomplete_details": {"reason": "max_output_tokens"},
            "output": [{"content": [{"text": "private response"}]}],
        }})

        with self.assertRaisesRegex(RuntimeError, "max_output_tokens") as raised:
            _completed_response(stream)

        self.assertNotIn("private response", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
