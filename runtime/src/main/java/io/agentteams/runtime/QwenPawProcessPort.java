package io.agentteams.runtime;

import java.util.UUID;

public interface QwenPawProcessPort {
    void start(AgentRuntimeContext context, RuntimeResultSink resultSink);

    void submit(RuntimeTask task);

    void cancel(UUID taskId);

    void stop();
}
