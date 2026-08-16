package io.agentteams.contracts.v1;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolCompatibilityTest {

    @Test
    void rejectsUnsupportedMajorVersion() {
        ProtocolVersion local = version(2, 1);

        assertFalse(ProtocolCompatibility.isCompatible(local, version(1, 9)));
        assertFalse(ProtocolCompatibility.isCompatible(local, version(3, 0)));
    }

    @Test
    void acceptsPeerWithSameMajorAndNoNewerMinorVersion() {
        ProtocolVersion local = version(2, 3);

        assertTrue(ProtocolCompatibility.isCompatible(local, version(2, 0)));
        assertTrue(ProtocolCompatibility.isCompatible(local, version(2, 3)));
        assertFalse(ProtocolCompatibility.isCompatible(local, version(2, 4)));
    }

    private static ProtocolVersion version(int major, int minor) {
        return ProtocolVersion.newBuilder().setMajor(major).setMinor(minor).build();
    }

}
