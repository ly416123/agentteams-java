import importlib.util
from pathlib import Path
from unittest import TestCase
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("run-kind-lease-recovery.py")
SPEC = importlib.util.spec_from_file_location("run_kind_lease_recovery", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class LeaseRecoveryScriptTest(TestCase):
    @patch.object(MODULE.time, "sleep")
    @patch.object(MODULE, "qwenpaw_agent_phases", return_value={"agent-1": "READY"})
    @patch.object(MODULE, "gateway_connection_state",
                  side_effect=[("old-connection", "ONLINE"),
                               ("new-connection", "OFFLINE"),
                               ("new-connection", "ONLINE")])
    def test_waits_for_a_new_online_gateway_connection(self, connection_state, _phase, _sleep):
        result = MODULE.wait_for_new_gateway_connection(
            "agentteams", "postgresql-0", "agent-1", "old-connection", timeout=1, interval=0
        )
        self.assertEqual("new-connection", result)
        self.assertEqual(3, connection_state.call_count)

    @patch.object(MODULE.time, "sleep")
    @patch.object(MODULE, "gateway_connection_state",
                  side_effect=[("old-connection", "ONLINE"), ("old-connection", "OFFLINE")])
    def test_waits_for_gateway_disconnect_before_reseeding_agent_phase(self, connection_state, _sleep):
        result = MODULE.wait_for_gateway_disconnection(
            "agentteams", "postgresql-0", "agent-1", timeout=1, interval=0
        )
        self.assertEqual(("old-connection", "OFFLINE"), result)
        self.assertEqual(2, connection_state.call_count)

    @patch.object(MODULE.time, "sleep")
    @patch.object(MODULE.time, "monotonic", side_effect=[0, 0, 2])
    def test_reports_last_wait_error(self, _monotonic, _sleep):
        with self.assertRaisesRegex(RuntimeError, "last_error='gateway unavailable'"):
            MODULE.wait_until("Gateway registration", lambda: (_ for _ in ()).throw(
                RuntimeError("gateway unavailable")), timeout=1, interval=0)
