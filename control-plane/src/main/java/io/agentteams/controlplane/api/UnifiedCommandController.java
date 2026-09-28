package io.agentteams.controlplane.api;

import io.agentteams.application.api.CommandEnvelope;
import io.agentteams.application.api.CommandResult;
import io.agentteams.controlplane.command.CommandDispatcher;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.security.ExecutionContextResolver;
import io.agentteams.controlplane.security.PrincipalContext;
import java.util.Objects;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Single authenticated ingress for cross-channel command envelopes. */
@RestController
@RequestMapping("/api/v1/commands")
public final class UnifiedCommandController {
    private final CommandDispatcher dispatcher;
    private final ExecutionContextResolver contexts;

    public UnifiedCommandController(CommandDispatcher dispatcher, ExecutionContextResolver contexts) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    @PostMapping
    public CommandResult execute(@RequestBody CommandEnvelope command) {
        ExecutionContext context = PrincipalContext.current().map(contexts::resolve)
                .orElseThrow(() -> new AuthorizationException("authentication required"));
        return dispatcher.dispatch(context, Objects.requireNonNull(command, "command"));
    }
}
