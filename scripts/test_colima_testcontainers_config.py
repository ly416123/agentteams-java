import pathlib
import unittest
import xml.etree.ElementTree as ET


ROOT = pathlib.Path(__file__).resolve().parents[1]
POM = ROOT / "pom.xml"
MAVEN_NS = "{http://maven.apache.org/POM/4.0.0}"


class ColimaTestcontainersConfigTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pom = ET.parse(POM).getroot()

    def _colima_profile(self):
        profiles = self.pom.find(f"{MAVEN_NS}profiles")
        for profile in profiles.findall(f"{MAVEN_NS}profile"):
            profile_id = profile.findtext(f"{MAVEN_NS}id")
            if profile_id == "colima-testcontainers":
                return profile
        self.fail("pom.xml must define the colima-testcontainers profile")

    def test_profile_activates_only_for_macos_with_colima_socket(self):
        profile = self._colima_profile()
        activation = profile.find(f"{MAVEN_NS}activation")
        self.assertIsNone(activation.find(f"{MAVEN_NS}activeByDefault"))
        self.assertEqual("mac", activation.find(f"{MAVEN_NS}os/{MAVEN_NS}family").text)
        self.assertEqual(
            "${user.home}/.colima/default/docker.sock",
            activation.find(f"{MAVEN_NS}file/{MAVEN_NS}exists").text,
        )

    def test_surefire_and_failsafe_export_colima_compatible_settings(self):
        profile = self._colima_profile()
        plugins = profile.findall(f"{MAVEN_NS}build/{MAVEN_NS}plugins/{MAVEN_NS}plugin")
        by_artifact = {
            plugin.findtext(f"{MAVEN_NS}artifactId"): plugin for plugin in plugins
        }
        for artifact_id in ("maven-surefire-plugin", "maven-failsafe-plugin"):
            variables = by_artifact[artifact_id].find(
                f"{MAVEN_NS}configuration/{MAVEN_NS}environmentVariables"
            )
            self.assertIsNotNone(variables)
            self.assertEqual(
                "unix://${user.home}/.colima/default/docker.sock",
                variables.findtext(f"{MAVEN_NS}DOCKER_HOST"),
            )
            self.assertEqual(
                "/var/run/docker.sock",
                variables.findtext(f"{MAVEN_NS}TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE"),
            )

    def test_configuration_is_portable_and_does_not_embed_a_developer_home(self):
        self.assertNotIn("/Users/gecko", POM.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
