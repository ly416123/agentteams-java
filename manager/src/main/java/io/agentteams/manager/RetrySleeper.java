package io.agentteams.manager;

import java.time.Duration;

@FunctionalInterface
public interface RetrySleeper {
    void sleep(Duration duration) throws InterruptedException;

    static RetrySleeper system() {
        return duration -> Thread.sleep(duration.toMillis());
    }
}
