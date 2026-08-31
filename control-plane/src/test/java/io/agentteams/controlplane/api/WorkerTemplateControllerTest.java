package io.agentteams.controlplane.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.template.WorkerTemplate;
import io.agentteams.controlplane.template.WorkerTemplateInstance;
import io.agentteams.controlplane.template.WorkerTemplateService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkerTemplateControllerTest {
    @Test
    void createRequiresIdempotencyKey() throws Exception {
        WorkerTemplateService service = mock(WorkerTemplateService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new WorkerTemplateController(service))
                .setControllerAdvice(new ApiErrorHandler()).build();

        mvc.perform(post("/api/v1/worker-templates")
                .contentType("application/json")
                .content("{\"name\":\"demo\",\"displayName\":\"Demo\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createReturnsCreatedTemplate() throws Exception {
        WorkerTemplateService service = mock(WorkerTemplateService.class);
        UUID id = UUID.randomUUID();
        when(service.create(eq("key"), any(), any())).thenReturn(
                new WorkerTemplate(id, "tenant", "project", "demo", "Demo", null, 0,
                        Instant.parse("2026-08-31T00:00:00Z"), Instant.parse("2026-08-31T00:00:00Z"), 0));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new WorkerTemplateController(service)).build();

        mvc.perform(post("/api/v1/worker-templates")
                .header("Idempotency-Key", "key")
                .contentType("application/json")
                .content("{\"name\":\"demo\",\"displayName\":\"Demo\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void upgradeRequiresIdempotencyKeyAndRoutesToService() throws Exception {
        WorkerTemplateService service = mock(WorkerTemplateService.class);
        UUID templateId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        when(service.upgrade(eq(templateId), eq(instanceId), eq(2L), eq("upgrade-key"))).thenReturn(
                new WorkerTemplateInstance(instanceId, templateId, 1, UUID.randomUUID(), UUID.randomUUID(),
                        "SUCCEEDED", 2, "instance-key", "hash", Instant.now(), Instant.now(), 2));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new WorkerTemplateController(service)).build();

        mvc.perform(post("/api/v1/worker-templates/{templateId}/instances/{instanceId}/upgrade/{revision}",
                        templateId, instanceId, 2)
                .header("Idempotency-Key", "upgrade-key"))
                .andExpect(status().isOk());
    }
}
