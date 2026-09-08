package io.agentteams.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class QwenPawWorkerEventRateLimitTest {

    @Test
    void eventRateLimitDefaultsToThirtyPerSecond() {
        QwenPawWorker.WorkerConfiguration configuration =
                QwenPawWorker.WorkerConfiguration.from(Map.of("AGENTTEAMS_AGENT_ID", "w1"));
        assertEquals(30, configuration.eventRateLimit());
    }

    @Test
    void eventRateLimitIsConfigurable() {
        QwenPawWorker.WorkerConfiguration configuration = QwenPawWorker.WorkerConfiguration.from(Map.of(
                "AGENTTEAMS_AGENT_ID", "w1", "AGENTTEAMS_EVENT_RATE_LIMIT", "5"));
        assertEquals(5, configuration.eventRateLimit());
    }
}
