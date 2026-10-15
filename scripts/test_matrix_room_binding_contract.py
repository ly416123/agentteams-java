#!/usr/bin/env python3
"""Guard the Matrix room binding and event projection contract."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class MatrixRoomBindingContractTest(unittest.TestCase):
    def test_kind_smoke_binds_room_and_removes_its_fixture(self):
        smoke = (ROOT / "scripts/smoke-kind-matrix.sh").read_text(encoding="utf-8")
        self.assertIn("INSERT INTO matrix_room_bindings", smoke)
        self.assertIn("'PROJECT', project.id", smoke)
        self.assertIn("'${room_id}'", smoke)
        self.assertIn("DELETE FROM matrix_room_bindings", smoke)
        self.assertIn("AND created_by='matrix-smoke'", smoke)

    def test_room_binding_schema_keeps_active_scope_unique(self):
        migration = (ROOT / "control-plane/src/main/resources/db/migration/V98__matrix_room_context_binding.sql").read_text(
            encoding="utf-8"
        )
        self.assertIn("matrix_room_bindings", migration)
        self.assertIn("matrix_room_bindings_active_room_idx", migration)
        self.assertIn("matrix_room_bindings_active_context_idx", migration)

    def test_channel_binding_schema_references_room_binding_history(self):
        migration = (ROOT / "control-plane/src/main/resources/db/migration/V99__matrix_channel_room_binding_reference.sql").read_text(
            encoding="utf-8"
        )
        self.assertIn("room_binding_id UUID REFERENCES matrix_room_bindings(id)", migration)
        self.assertIn("context_type", migration)
        self.assertIn("matrix_channel_bindings_room_reference_idx", migration)

    def test_projection_is_deterministic_and_filters_internal_events(self):
        projector = (ROOT / "control-plane/src/main/java/io/agentteams/controlplane/matrix/MatrixEventProjector.java").read_text(
            encoding="utf-8"
        )
        self.assertIn("UUID.nameUUIDFromBytes", projector)
        self.assertIn('"INTERNAL".equalsIgnoreCase(event.visibility())', projector)
        self.assertIn("ProjectionStatus.DUPLICATE", projector)

    def test_revoked_linked_channel_binding_cannot_send(self):
        adapter = (ROOT / "control-plane/src/main/java/io/agentteams/controlplane/channel/MatrixChannelAdapter.java").read_text(
            encoding="utf-8"
        )
        self.assertIn("roomBindingId()", adapter)
        self.assertIn("MatrixRoomBindingStatus.ACTIVE", adapter)
        self.assertIn("PERMANENT_REJECTION", adapter)


if __name__ == "__main__":
    unittest.main()
