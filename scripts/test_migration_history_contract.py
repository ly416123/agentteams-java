#!/usr/bin/env python3
"""Guard the append-only Flyway history shared by development environments."""

from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "control-plane/src/main/resources/db/migration"


def migration_files() -> dict[int, Path]:
    result: dict[int, Path] = {}
    for path in MIGRATIONS.glob("V*__*.sql"):
        match = re.fullmatch(r"V(\d+)__.+\.sql", path.name)
        assert match, f"unexpected migration filename: {path.name}"
        version = int(match.group(1))
        assert version not in result, f"duplicate Flyway version: V{version}"
        result[version] = path
    return result


def test_existing_l5_history_is_preserved_and_new_work_is_appended() -> None:
    files = migration_files()
    expected_legacy = {
        72: "V72__platform_identity_and_integrations.sql",
        73: "V73__principal_membership_scope.sql",
        74: "V74__remove_provisioning_foreign_keys.sql",
        75: "V75__integration_credential_management_metadata.sql",
        76: "V76__provisioning_policy_version_state.sql",
    }
    for version, filename in expected_legacy.items():
        assert files[version].name == filename

    for version in range(77, 84):
        assert version in files, f"missing appended migration V{version}"


def test_l5_compatibility_migration_is_idempotent_for_legacy_identity_schema() -> None:
    files = migration_files()
    sql = files[80].read_text()
    for marker in (
        "ALTER TABLE integrations",
        "CREATE TABLE IF NOT EXISTS management_users",
        "ALTER TABLE external_identities",
        "CREATE TABLE IF NOT EXISTS integration_provisioning_policies",
    ):
        assert marker in sql, f"V80 must bridge the legacy L5 schema: {marker}"


if __name__ == "__main__":
    test_existing_l5_history_is_preserved_and_new_work_is_appended()
    test_l5_compatibility_migration_is_idempotent_for_legacy_identity_schema()
    print("MIGRATION_HISTORY_CONTRACT_OK")
