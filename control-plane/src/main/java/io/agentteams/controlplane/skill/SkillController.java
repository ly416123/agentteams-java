package io.agentteams.controlplane.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/skills")
public final class SkillController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private final SkillService service;
    private final ObjectMapper objectMapper;

    public SkillController(SkillService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<SkillResponse> createSkill(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody CreateSkillRequest request) {
        requireRequest(request);
        requireIdempotencyKey(idempotencyKey);
        return ResponseEntity.status(201).body(SkillResponse.from(service.createSkill(idempotencyKey,
                new SkillService.SkillInput(request.name(), request.displayName(), request.description(),
                        request.visibility()))));
    }

    @GetMapping
    public List<SkillResponse> listSkills() {
        return service.listSkills().stream().map(SkillResponse::from).toList();
    }

    @GetMapping("/{skillId}")
    public SkillResponse getSkill(@PathVariable UUID skillId) {
        return SkillResponse.from(service.getSkill(skillId));
    }

    @PostMapping("/{skillId}/versions")
    public ResponseEntity<SkillVersionResponse> createVersion(
            @PathVariable UUID skillId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody CreateVersionRequest request) {
        requireRequest(request);
        requireIdempotencyKey(idempotencyKey);
        SkillService.VersionInput input = new SkillService.VersionInput(request.version(), request.digest(),
                json(request.manifest()), request.visibility());
        return ResponseEntity.status(201).body(SkillVersionResponse.from(
                service.createVersion(skillId, idempotencyKey, input), objectMapper));
    }

    @GetMapping("/{skillId}/versions")
    public List<SkillVersionResponse> listVersions(@PathVariable UUID skillId) {
        return service.listVersions(skillId).stream()
                .map(version -> SkillVersionResponse.from(version, objectMapper)).toList();
    }

    @PostMapping("/{skillId}/versions/{versionId}/publish")
    public SkillVersionResponse publish(@PathVariable UUID skillId, @PathVariable UUID versionId) {
        return SkillVersionResponse.from(service.publish(skillId, versionId), objectMapper);
    }

    @PostMapping("/{skillId}/versions/{versionId}/disable")
    public SkillVersionResponse disable(@PathVariable UUID skillId, @PathVariable UUID versionId) {
        return SkillVersionResponse.from(service.disable(skillId, versionId), objectMapper);
    }

    @ExceptionHandler(SkillIdempotencyConflictException.class)
    ResponseEntity<ApiError> idempotencyConflict(SkillIdempotencyConflictException ignored) {
        return ResponseEntity.status(409).body(new ApiError("CONFLICT", "idempotency key conflicts"));
    }

    @ExceptionHandler(SkillPackageValidationException.class)
    ResponseEntity<ApiError> invalidSkillPackage(SkillPackageValidationException error) {
        return ResponseEntity.badRequest().body(new ApiError("SKILL_PACKAGE_INVALID", error.getMessage()));
    }

    public record CreateSkillRequest(String name, String displayName, String description, String visibility) {
    }

    public record CreateVersionRequest(String version, String digest, JsonNode manifest, String visibility) {
    }

    public record SkillResponse(UUID id, String name, String displayName, String description, String visibility,
            String lifecycle, Instant createdAt, Instant updatedAt, long version) {

        static SkillResponse from(SkillRecord skill) {
            return new SkillResponse(skill.id(), skill.name(), skill.displayName(), skill.description(),
                    skill.visibility(), skill.lifecycle(), skill.createdAt(), skill.updatedAt(), skill.version());
        }
    }

    public record SkillVersionResponse(UUID id, UUID skillId, String version, String digest, JsonNode manifest,
            String visibility, String lifecycle, Instant createdAt, Instant updatedAt, long recordVersion) {

        static SkillVersionResponse from(SkillVersionRecord version, ObjectMapper objectMapper) {
            try {
                return new SkillVersionResponse(version.id(), version.skillId(), version.version(), version.digest(),
                        objectMapper.readTree(version.manifestJson()), version.visibility(), version.lifecycle(),
                        version.createdAt(), version.updatedAt(), version.recordVersion());
            } catch (IOException error) {
                throw new IllegalStateException("stored skill manifest is not valid JSON", error);
            }
        }
    }

    public record ApiError(String code, String message) {
    }

    private static String json(JsonNode value) {
        if (value == null || value.isNull()) {
            return "{}";
        }
        return value.toString();
    }

    private static void requireRequest(Object request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
    }

    private static void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        if (key.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key must be at most 255 characters");
        }
    }
}
