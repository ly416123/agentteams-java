package io.agentteams.controlplane.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class ConfigUploadCleanupJobTest {
    @Test
    void runsBoundedCleanupBatch() {
        ConfigUploadService uploads = mock(ConfigUploadService.class);
        ConfigUploadCleanupJob job = new ConfigUploadCleanupJob(uploads, 25);

        job.run();

        verify(uploads).cleanupExpired(25);
    }
}
