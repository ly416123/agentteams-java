package io.agentteams.manager.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.application.api.TaskCommandPort;
import io.agentteams.application.api.TaskCommandPort.TaskCreationResult;
import io.agentteams.manager.security.ManagerPrincipal;
import io.agentteams.manager.security.ManagerRequestContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ControlPlaneConversationTaskBridgeTest {
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000123");
    private static final UUID TEAM_ID = UUID.fromString("10000000-0000-0000-0000-000000000123");

    @AfterEach
    void clearManagerContext() {
        ManagerRequestContext.clear();
    }

    @Test
    void createsAndQueuesAnAgentScopeTaskInTheConversationTeam() {
        TaskCommandPort commands = mock(TaskCommandPort.class);
        TaskCreationResult created = new TaskCreationResult(UUID.randomUUID(), "DRAFT", 0);
        TaskCreationResult queued = new TaskCreationResult(created.taskId(), "QUEUED", 1);
        when(commands.create(any(), any())).thenReturn(created);
        when(commands.queue(eq(created.taskId()), eq(created.version()), any())).thenReturn(queued);
        ManagerRequestContext.set(new ManagerPrincipal("alice", "tenant-a", "project-a",
                TEAM_ID.toString(), Set.of("task:create")), "bearer");

        ControlPlaneConversationTaskBridge bridge = new ControlPlaneConversationTaskBridge(commands,
                new ObjectMapper());
        TaskCreationResult result = bridge.createAndQueue(
                new ConversationRuntimePort.Context("project-a", TEAM_ID.toString(), null, null, SESSION_ID),
                new ConversationOwner("tenant-a", "alice"), "请识别意图并分配给两个 Worker", "message-key");

        assertThat(result).isEqualTo(queued);
        verify(commands).create(argThat(key -> key.startsWith("conversation-task-")), argThat(command -> {
            String spec = command.specJson();
            return command.title().contains(SESSION_ID.toString())
                    && spec.contains("\"taskType\":\"agentscope\"")
                    && spec.contains("\"teamId\":\"" + TEAM_ID + "\"")
                    && spec.contains("\"sourceConversationId\":\"" + SESSION_ID + "\"")
                    && spec.contains("请识别意图并分配给两个 Worker");
        }));
        InOrder order = inOrder(commands);
        order.verify(commands).create(any(), any());
        order.verify(commands).queue(eq(created.taskId()), eq(0L), argThat(key ->
                key.startsWith("conversation-task-" + SESSION_ID + "-") && key.endsWith("-queue")));
    }

    @Test
    void usesAuthenticatedManagerScopeForTaskAuthorizationWhenConversationUsesCanonicalIds() {
        TaskCommandPort commands = mock(TaskCommandPort.class);
        TaskCreationResult created = new TaskCreationResult(UUID.randomUUID(), "DRAFT", 0);
        when(commands.create(any(), any())).thenReturn(created);
        when(commands.queue(eq(created.taskId()), eq(created.version()), any())).thenReturn(created);
        ManagerRequestContext.set(new ManagerPrincipal("alice", "tenant-a", "project-a",
                "team-a", Set.of("task:create")), "bearer");

        new ControlPlaneConversationTaskBridge(commands, new ObjectMapper()).createAndQueue(
                new ConversationRuntimePort.Context("ef5916eb-14d9-4e75-9e9c-041c6b5fb447",
                        TEAM_ID.toString(), null, null, SESSION_ID),
                new ConversationOwner("tenant-a", "alice"), "hello", "message-key");

        verify(commands).create(any(), argThat(command -> command.specJson().contains(
                "\"scope\":{\"tenant\":\"tenant-a\",\"project\":\"project-a\",\"team\":\"team-a\"}")));
    }

    @Test
    void preservesBearerScopeWhenEffectiveManagerProjectIsCanonicalized() {
        TaskCommandPort commands = mock(TaskCommandPort.class);
        TaskCreationResult created = new TaskCreationResult(UUID.randomUUID(), "DRAFT", 0);
        when(commands.create(any(), any())).thenReturn(created);
        when(commands.queue(eq(created.taskId()), eq(created.version()), any())).thenReturn(created);
        ManagerRequestContext.set(
                new ManagerPrincipal("alice", "tenant-a", "ef5916eb-14d9-4e75-9e9c-041c6b5fb447",
                        TEAM_ID.toString(), Set.of("task:create")),
                "bearer",
                new ManagerPrincipal("alice", "tenant-a", "project-a", "team-a", Set.of("task:create")));

        new ControlPlaneConversationTaskBridge(commands, new ObjectMapper()).createAndQueue(
                new ConversationRuntimePort.Context("ef5916eb-14d9-4e75-9e9c-041c6b5fb447",
                        TEAM_ID.toString(), null, null, SESSION_ID),
                new ConversationOwner("tenant-a", "alice"), "hello", "message-key");

        verify(commands).create(any(), argThat(command -> command.specJson().contains(
                "\"scope\":{\"tenant\":\"tenant-a\",\"project\":\"project-a\",\"team\":\"team-a\"}")));
    }
}
