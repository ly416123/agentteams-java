package io.agentteams.controlplane.agentspec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.domain.agent.WorkerType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class AgentSpecService {

    private static final String DRAFT = "DRAFT";
    private static final String PUBLISHED = "PUBLISHED";
    private static final String DISABLED = "DISABLED";

    private final AgentSpecRepository repository;
    private final AgentSpecModelCatalog modelCatalog;
    private final Clock clock;
    private final AgentSpecSchemaValidator schemaValidator;
    private final AgentSpecReferenceParser referenceParser;
    private final AgentSpecReferenceValidator referenceValidator;
    private final io.agentteams.controlplane.security.ResourceScopeRepository resourceScopes;

    public AgentSpecService(AgentSpecRepository repository, AgentSpecModelCatalog modelCatalog) {
        this(repository, modelCatalog, Clock.systemUTC(), new AgentSpecSchemaValidator(),
                new NoopAgentSpecReferenceValidator(), null);
    }

    AgentSpecService(AgentSpecRepository repository, AgentSpecModelCatalog modelCatalog, Clock clock) {
        this(repository, modelCatalog, clock, new AgentSpecSchemaValidator(),
                new NoopAgentSpecReferenceValidator(), null);
    }

    AgentSpecService(AgentSpecRepository repository, AgentSpecModelCatalog modelCatalog, Clock clock,
            AgentSpecSchemaValidator schemaValidator) {
        this(repository, modelCatalog, clock, schemaValidator, new NoopAgentSpecReferenceValidator());
    }

    @Autowired
    public AgentSpecService(AgentSpecRepository repository, AgentSpecModelCatalog modelCatalog, Clock clock,
            ObjectProvider<AgentSpecReferenceValidator> referenceValidators,
            ObjectProvider<io.agentteams.controlplane.security.ResourceScopeRepository> scopes) {
        this(repository, modelCatalog, clock, new AgentSpecSchemaValidator(),
                referenceValidators.getIfAvailable(NoopAgentSpecReferenceValidator::new),
                scopes.getIfAvailable());
    }

    AgentSpecService(AgentSpecRepository repository, AgentSpecModelCatalog modelCatalog, Clock clock,
            AgentSpecSchemaValidator schemaValidator, AgentSpecReferenceValidator referenceValidator) {
        this(repository, modelCatalog, clock, schemaValidator, referenceValidator, null);
    }

    AgentSpecService(AgentSpecRepository repository, AgentSpecModelCatalog modelCatalog, Clock clock,
            AgentSpecSchemaValidator schemaValidator, AgentSpecReferenceValidator referenceValidator,
            io.agentteams.controlplane.security.ResourceScopeRepository resourceScopes) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.modelCatalog = Objects.requireNonNull(modelCatalog, "modelCatalog");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.schemaValidator = Objects.requireNonNull(schemaValidator, "schemaValidator");
        this.referenceParser = new AgentSpecReferenceParser();
        this.referenceValidator = Objects.requireNonNull(referenceValidator, "referenceValidator");
        this.resourceScopes = resourceScopes;
    }

    @Transactional
    public AgentSpecRecord create(String idempotencyKey, Input input) {
        String key = required(idempotencyKey, "Idempotency-Key");
        Objects.requireNonNull(input, "input");
        String hash = hash(input);
        var existing = repository.findIdempotency(key);
        if (existing.isPresent()) {
            if (!existing.get().requestHash().equals(hash)) {
                throw new IllegalArgumentException("Idempotency-Key was already used with a different request");
            }
            return get(existing.get().specId());
        }

        String name = required(input.name(), "name");
        String runtime = required(input.runtime(), "runtime");
        String providerName = required(input.modelProvider(), "modelProvider");
        String modelId = required(input.modelName(), "modelName");
        String desiredState = normalizeState(input.desiredState());
        String specJson = objectJson(input.specJson());
        schemaValidator.validate(specJson);
        validateModelReference(providerName, modelId);

        Instant now = clock.instant();
        String tenantId = PrincipalContext.current().map(p -> p.scope().tenant()).orElse(null);
        String projectId = PrincipalContext.current().map(p -> p.scope().project()).orElse(null);
        WorkerType workerType = input.workerType() == null ? WorkerType.EXECUTOR : input.workerType();
        AgentSpecRecord record = new AgentSpecRecord(UUID.randomUUID(), name, workerType, runtime, providerName,
                modelId, optional(input.teamRef()), desiredState, DRAFT, specJson, now, now, 1, tenantId, projectId);
        if (!repository.insertIdempotency(new AgentSpecRepository.IdempotencyRecord(key, hash, record.id(), now))) {
            var winner = repository.findIdempotency(key).orElseThrow();
            if (!winner.requestHash().equals(hash)) {
                throw new IllegalArgumentException("Idempotency-Key was already used with a different request");
            }
            return get(winner.specId());
        }
        repository.insert(record);
        return record;
    }

    public List<AgentSpecRecord> list() {
        return repository.findAll().stream().filter(this::visibleToCurrentPrincipal).toList();
    }

    /** Validates the Project route against the authenticated Project scope. */
    public void requireProjectScope(String projectId) {
        if (projectId == null || projectId.isBlank()) return;
        io.agentteams.controlplane.security.Principal principal = PrincipalContext.current()
                .orElseThrow(() -> new AuthorizationException("authentication required"));
        if (projectId.equals(principal.scope().project())) return;
        if (resourceScopes == null || !resourceScopes.matchesCallerProject(projectId)) {
            throw new AuthorizationException("resource is outside caller project");
        }
    }

    public AgentSpecRecord get(UUID id) {
        AgentSpecRecord record = repository.findById(Objects.requireNonNull(id, "id"))
                .orElseThrow(() -> new IllegalArgumentException("agent spec does not exist: " + id));
        if (!visibleToCurrentPrincipal(record)) {
            throw new AuthorizationException("agent spec is outside caller project");
        }
        return record;
    }

    @Transactional
    public AgentSpecRecord publish(String idempotencyKey, UUID id) {
        return transition(idempotencyKey, id, "PUBLISH", PUBLISHED, DRAFT);
    }

    @Transactional
    public AgentSpecRecord deactivate(String idempotencyKey, UUID id) {
        return transition(idempotencyKey, id, "DEACTIVATE", DISABLED, PUBLISHED);
    }

    public record Input(String name, String runtime, String modelProvider, String modelName,
            String teamRef, String desiredState, WorkerType workerType, String specJson) {
        public Input(String name, String runtime, String modelProvider, String modelName,
                String teamRef, String desiredState, String specJson) {
            this(name, runtime, modelProvider, modelName, teamRef, desiredState, WorkerType.EXECUTOR, specJson);
        }
    }

    private static String normalizeState(String value) {
        String state = value == null || value.isBlank() ? "RUNNING" : value.trim().toUpperCase();
        if (!state.equals("RUNNING") && !state.equals("STOPPED")) {
            throw new IllegalArgumentException("desiredState must be RUNNING or STOPPED");
        }
        return state;
    }

    private static String objectJson(String value) {
        if (value == null || value.isBlank()) return "{}";
        String json = value.trim();
        if (!json.startsWith("{") || !json.endsWith("}")) {
            throw new IllegalArgumentException("spec must be a JSON object");
        }
        return json;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void validateModelReference(String providerName, String modelId) {
        AgentSpecModelCatalog.ProviderReference provider = modelCatalog.findProviderByName(providerName)
                .orElseThrow(() -> new IllegalArgumentException("model provider does not exist: " + providerName));
        if (!provider.enabled()) {
            throw new IllegalArgumentException("model provider is disabled: " + providerName);
        }
        AgentSpecModelCatalog.ModelReference model = modelCatalog.findModelById(provider.id(), modelId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "model does not exist for provider: " + providerName + "/" + modelId));
        if (!model.enabled()) {
            throw new IllegalArgumentException("model is disabled for provider: " + providerName + "/" + modelId);
        }
    }

    private AgentSpecRecord transition(String idempotencyKey, UUID id, String operation, String target,
            String requiredCurrent) {
        String key = required(idempotencyKey, "Idempotency-Key");
        UUID specId = Objects.requireNonNull(id, "id");
        String requestHash = transitionHash(operation, specId);
        var existing = repository.findIdempotency(key);
        if (existing.isPresent()) {
            if (!existing.get().requestHash().equals(requestHash)) {
                throw new IllegalArgumentException("Idempotency-Key was already used with a different request");
            }
            return get(existing.get().specId());
        }

        AgentSpecRecord current = get(specId);
        if (!requiredCurrent.equals(current.lifecycleStatus())) {
            throw new IllegalArgumentException("cannot " + operation.toLowerCase()
                    + " agent spec from lifecycle status: " + current.lifecycleStatus());
        }
        if ("PUBLISH".equals(operation)) {
            validateReferences(current);
        }

        Instant now = clock.instant();
        AgentSpecRecord next = new AgentSpecRecord(current.id(), current.name(), current.workerType(), current.runtime(),
                current.modelProvider(), current.modelName(), current.teamRef(), current.desiredState(), target,
                current.specJson(), current.createdAt(), now, current.version() + 1, current.tenantId(),
                current.projectId());
        if (!repository.insertIdempotency(new AgentSpecRepository.IdempotencyRecord(key, requestHash,
                current.id(), now))) {
            return resolveTransitionWinner(key, requestHash);
        }
        repository.updateLifecycle(next, current.version());
        return next;
    }

    private AgentSpecRecord resolveTransitionWinner(String key, String requestHash) {
        var winner = repository.findIdempotency(key).orElseThrow();
        if (!winner.requestHash().equals(requestHash)) {
            throw new IllegalArgumentException("Idempotency-Key was already used with a different request");
        }
        return get(winner.specId());
    }

    private void validateReferences(AgentSpecRecord record) {
        AgentSpecReferences references = referenceParser.parse(record.specJson())
                .withModelRef(new AgentSpecReferences.ModelRef(record.modelProvider(), record.modelName()));
        AgentSpecReferenceValidationRequest request = PrincipalContext.current()
                .map(principal -> new AgentSpecReferenceValidationRequest(
                        principal.scope().tenant(), principal.scope().project(), principal.scope().team(), references))
                .orElseGet(() -> new AgentSpecReferenceValidationRequest(
                        record.tenantId(), record.projectId(), record.teamRef(), references));
        AgentSpecReferenceValidationResult result = referenceValidator.validate(request);
        if (!result.isValid()) {
            throw new AgentSpecReferenceValidationException(result);
        }
    }

    private static String transitionHash(String operation, UUID id) {
        return hashValue(operation + "\u0000" + id);
    }

    private boolean visibleToCurrentPrincipal(AgentSpecRecord record) {
        return PrincipalContext.current().map(principal -> {
            AuthorizationService.Scope scope = principal.scope();
            return record.tenantId() != null && record.projectId() != null
                    && record.tenantId().equals(scope.tenant()) && record.projectId().equals(scope.project());
        }).orElse(true);
    }

    private static String hashValue(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String hash(Input input) {
        String value = String.join("\u0000", Objects.toString(input.name(), ""),
                Objects.toString(input.workerType(), ""), Objects.toString(input.runtime(), ""), Objects.toString(input.modelProvider(), ""),
                Objects.toString(input.modelName(), ""), Objects.toString(input.teamRef(), ""),
                Objects.toString(input.desiredState(), ""), Objects.toString(input.specJson(), ""));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
