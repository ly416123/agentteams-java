package io.agentteams.manager;

import io.agentteams.application.api.QuotaPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.manager.session.JdbcManagerSessionRepository;
import io.agentteams.manager.session.ManagerSessionRepository;
import io.agentteams.manager.session.ManagerSessionServiceFacade;
import io.agentteams.manager.conversation.ConversationRuntimePort;
import io.agentteams.manager.conversation.ConversationService;
import io.agentteams.manager.conversation.ConversationRepository;
import io.agentteams.manager.conversation.ControlPlaneConversationPort;
import io.agentteams.manager.conversation.ControlPlaneConversationTaskBridge;
import io.agentteams.manager.conversation.ConversationTaskBridge;
import io.agentteams.manager.conversation.ConversationIntentRouter;
import io.agentteams.manager.conversation.HttpControlPlaneConversationPort;
import io.agentteams.manager.conversation.JdbcConversationRepository;
import io.agentteams.manager.conversation.FakeConversationRuntime;
import io.agentteams.manager.security.ConversationScopeAuthorizer;
import io.agentteams.manager.security.ControlPlaneConversationScopeAuthorizer;
import io.agentteams.manager.security.ManagerRequestContext;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import io.grpc.ManagedChannelBuilder;

@SpringBootApplication
@EnableTransactionManagement
public class ManagerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ManagerApplication.class, args);
    }

    @Bean
    ManagerSessionRepository managerSessionRepository(JdbcTemplate jdbc) {
        return new JdbcManagerSessionRepository(jdbc);
    }

    @Bean
    ModelProvider managerModelProvider(Environment environment) {
        String model = environment.getProperty("AGENTTEAMS_MODEL", ChatGptPlanResponsesProvider.DEFAULT_MODEL);
        String tokenFile = environment.getProperty("AGENTTEAMS_CHATGPT_ACCESS_TOKEN_FILE",
                "/var/run/agentteams/chatgpt/token");
        String proxy = environment.getProperty("AGENTTEAMS_CHATGPT_PROXY_URL", "");
        return new ChatGptPlanResponsesProvider(model, java.nio.file.Path.of(tokenFile),
                ChatGptPlanResponsesProvider.DEFAULT_ENDPOINT,
                ChatGptPlanResponsesProvider.httpClient(proxy), java.time.Duration.ofSeconds(90));
    }

    @Bean
    io.agentteams.application.api.TaskCommandPort taskCommandPort(
            @Value("${AGENTTEAMS_CONTROL_PLANE_URL:}") String controlPlaneUrl,
            ObjectMapper mapper) {
        return new HttpTaskCommandPort(controlPlaneUrl, HttpClient.newHttpClient(), mapper);
    }

    @Bean
    ControlPlaneConversationPort controlPlaneConversationPort(
            @Value("${AGENTTEAMS_CONTROL_PLANE_URL:}") String controlPlaneUrl,
            ObjectMapper mapper) {
        return new HttpControlPlaneConversationPort(controlPlaneUrl, HttpClient.newHttpClient(), mapper);
    }

    @Bean
    ManagerToolRegistry managerToolRegistry(io.agentteams.application.api.TaskCommandPort taskCommands,
            ObjectMapper mapper) {
        ControlPlaneCreateTaskTool createTask = new ControlPlaneCreateTaskTool(taskCommands, mapper);
        return new ManagerToolRegistry(Map.of("create_task", new ManagerToolRegistry.Tool(
                "task:create", false, (input, context) -> createTask.create((CreateTaskIntent) input, context))));
    }

    @Bean
    ManagerSessionService managerSessionService(ModelProvider provider, ObjectMapper mapper,
            ManagerToolRegistry tools, ModelCallAuditor auditor, ModelCallAdmission admission) {
        return new ManagerSessionService(provider, mapper, tools, auditor, Clock.systemUTC(), admission);
    }

    @Bean
    JdbcModelCallAuditor managerModelCallAuditor(JdbcTemplate jdbc) {
        return new JdbcModelCallAuditor(jdbc);
    }

    @Bean
    ModelCallAdmission managerModelCallAdmission(QuotaPort quotaPort) {
        return new ProjectScopedModelCallAdmission(quotaPort);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "agentteams.quota", name = "remote-enabled", havingValue = "true")
    GrpcQuotaPort managerRemoteQuotaPort(
            @Value("${AGENTTEAMS_GATEWAY_HOST:agentteams-agentteams-java-gateway}") String gatewayHost,
            @Value("${AGENTTEAMS_GATEWAY_PORT:9090}") int gatewayPort,
            @Value("${AGENTTEAMS_MANAGER_ID:manager}") String managerId) {
        var channel = ManagedChannelBuilder.forAddress(gatewayHost, gatewayPort).usePlaintext().build();
        QuotaPort quota = ManagerQuotaPortFactory.fromEnvironment(channel, managerId);
        if (!(quota instanceof GrpcQuotaPort grpcQuota)) {
            channel.shutdown();
            throw new IllegalStateException("remote quota is enabled but quota port is not configured");
        }
        return grpcQuota;
    }

    @Bean
    QuotaPort managerQuotaPort(java.util.Optional<GrpcQuotaPort> remoteQuota) {
        return remoteQuota.<QuotaPort>map(quota -> quota).orElseGet(QuotaPort::noop);
    }

    @Bean
    ManagerSessionServiceFacade managerSessionServiceFacade(ManagerSessionRepository repository,
            ManagerSessionService modelService) {
        return new ManagerSessionServiceFacade(repository, modelService, Clock.systemUTC());
    }

    @Bean
    ConversationTaskBridge conversationTaskBridge(io.agentteams.application.api.TaskCommandPort taskCommands,
            ObjectMapper mapper) {
        return new ControlPlaneConversationTaskBridge(taskCommands, mapper);
    }

    @Bean
    ConversationIntentRouter conversationIntentRouter(ManagerSessionService modelService) {
        return (context, owner, content) -> {
            var principal = ManagerRequestContext.require();
            var modelContext = new ManagerToolRegistry.ToolContext(principal.permissions(), false,
                    owner.tenantId(), context.project(), context.worker(), context.task(), context.team(),
                    "conversation.intent", null, null, context.sessionId().toString());
            return modelService.routeConversationMessage(content, modelContext);
        };
    }

    @Bean(destroyMethod = "close")
    ConversationRuntimePort conversationRuntime(
            @Value("${AGENTTEAMS_CONVERSATION_RUNTIME:fake}") String runtimeName) {
        if ("fake".equalsIgnoreCase(runtimeName)) {
            return new FakeConversationRuntime();
        }
        throw new IllegalArgumentException("unsupported conversation runtime: " + runtimeName);
    }

    @Bean
    ConversationRepository conversationRepository(JdbcTemplate jdbc) {
        return new JdbcConversationRepository(jdbc);
    }

    @Bean(destroyMethod = "close")
    ConversationService conversationService(ConversationRuntimePort runtime, ConversationRepository repository,
            ControlPlaneConversationPort controlPlane, ConversationTaskBridge taskBridge,
            ConversationIntentRouter intentRouter) {
        return new ConversationService(runtime, repository, controlPlane, taskBridge, intentRouter);
    }

    @Bean
    ConversationScopeAuthorizer conversationScopeAuthorizer(
            @Value("${AGENTTEAMS_CONTROL_PLANE_URL:}") String controlPlaneUrl) {
        return new ControlPlaneConversationScopeAuthorizer(controlPlaneUrl, HttpClient.newHttpClient());
    }

    /** Composition-test/local fallback that keeps the fake runtime constructor convenient. */
    ConversationService conversationService(ConversationRuntimePort runtime) {
        return new ConversationService(runtime);
    }
}
