package io.agentteams.controlplane.skill;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.controlplane.api.ApiErrorHandler;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class SkillControllerTest {

    @Mock
    private SkillService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SkillController(service, new ObjectMapper()))
                .setControllerAdvice(new ApiErrorHandler()).build();
    }

    @Test
    void createsSkillAndRequiresIdempotencyKey() throws Exception {
        UUID skillId = UUID.randomUUID();
        SkillRecord skill = new SkillRecord(skillId, "code-review", "Code Review", "", "PRIVATE", "DRAFT",
                Instant.parse("2026-08-23T00:00:00Z"), Instant.parse("2026-08-23T00:00:00Z"), 0);
        when(service.createSkill(eq("skill-key"), any())).thenReturn(skill);

        mockMvc.perform(post("/api/v1/skills")
                        .header("Idempotency-Key", "skill-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"code-review\",\"displayName\":\"Code Review\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(skillId.toString()))
                .andExpect(jsonPath("$.lifecycle").value("DRAFT"));

        mockMvc.perform(post("/api/v1/skills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"code-review\",\"displayName\":\"Code Review\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listsSkillsAndVersions() throws Exception {
        UUID skillId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        SkillRecord skill = new SkillRecord(skillId, "code-review", "Code Review", "", "PUBLIC", "PUBLISHED",
                now, now, 1);
        SkillVersionRecord version = new SkillVersionRecord(versionId, skillId, "1.0.0", "sha256:abc",
                "{\"name\":\"code-review\"}", "PUBLIC", "PUBLISHED", now, now, 1);
        when(service.listSkills()).thenReturn(List.of(skill));
        when(service.listVersions(skillId)).thenReturn(List.of(version));

        mockMvc.perform(get("/api/v1/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("code-review"));

        mockMvc.perform(get("/api/v1/skills/{skillId}/versions", skillId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value("1.0.0"))
                .andExpect(jsonPath("$[0].digest").value("sha256:abc"))
                .andExpect(jsonPath("$[0].manifest.name").value("code-review"));
    }

    @Test
    void createsPublishesAndDisablesVersion() throws Exception {
        UUID skillId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        SkillVersionRecord version = new SkillVersionRecord(versionId, skillId, "1.0.0", "sha256:abc", "{}",
                "PRIVATE", "PUBLISHED", now, now, 1);
        when(service.createVersion(eq(skillId), eq("version-key"), any())).thenReturn(version);
        when(service.publish(skillId, versionId)).thenReturn(version);
        when(service.disable(skillId, versionId)).thenReturn(version);

        mockMvc.perform(post("/api/v1/skills/{skillId}/versions", skillId)
                        .header("Idempotency-Key", "version-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"1.0.0\",\"digest\":\"sha256:abc\","
                                + "\"manifest\":{\"name\":\"code-review\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.digest").value("sha256:abc"));

        mockMvc.perform(post("/api/v1/skills/{skillId}/versions/{versionId}/publish", skillId, versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("PUBLISHED"));

        mockMvc.perform(post("/api/v1/skills/{skillId}/versions/{versionId}/disable", skillId, versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(versionId.toString()));
    }
}
