package io.agentteams.controlplane.application;

import io.agentteams.application.api.TaskCommandPort;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.service.TaskService;
import java.util.Objects;

/** Maps the application contract to the Control Plane persistence-backed service. */
public final class ControlPlaneTaskCommandAdapter implements TaskCommandPort {
    private final TaskService tasks;

    public ControlPlaneTaskCommandAdapter(TaskService tasks) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
    }

    @Override
    public TaskCreationResult create(String idempotencyKey, TaskCreateCommand command) {
        Objects.requireNonNull(command, "command");
        TaskRecord task = tasks.create(idempotencyKey, new TaskService.TaskInput(command.title(),
                command.description(), command.specJson(), command.actor(), command.source()));
        return new TaskCreationResult(task.id(), task.phase().name(), task.version());
    }
}
