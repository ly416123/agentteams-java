package io.agentteams.manager;

import io.agentteams.application.api.QuotaPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.manager.session.JdbcManagerSessionRepository;
import io.agentteams.manager.session.ManagerSessionRepository;
import io.agentteams.manager.session.ManagerSessionServiceFacade;
import io.agentteams.manager.conversation.ConversationRuntimeConfiguration;
import io.agentteams.manager.conversation.ConversationRuntimePort;
import io.agentteams.manager.conversation.ConversationService;
import io.agentteams.manager.conversation.ConversationRepository;
import io.agentteams.manager.conversation.JdbcConversationRepository;
import io.agentteams.manager.conversation.FakeConversationRuntime;
import io.agentteams.manager.conversation.QwenPawConversationRuntime;
import io.agentteams.manager.security.ConversationScopeAuthorizer;
import io.agentteams.manager.security.ControlPlaneConversationScopeAuthorizer;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
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
    ModelProvider managerModelProvider() {
        return DeepSeekConfiguration.fromEnvironment(System.getenv())
                .createProvider(HttpClient.newHttpClient(), new ObjectMapper());
    }

    @Bean
    io.agentteams.application.api.TaskCommandPort taskCommandPort(
            @Value("${AGENTTEAMS_CONTROL_PLANE_URL:}") String controlPlaneUrl,
            ObjectMapper mapper) {
        return new HttpTaskCommandPort(controlPlaneUrl, HttpClient.newHttpClient(), mapper);
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
    ConversationRuntimeConfiguration conversationRuntimeConfiguration(
            @Value("${AGENTTEAMS_CONVERSATION_QWENPAW_ENDPOINT:http://127.0.0.1:8088}") String endpoint,
            @Value("${AGENTTEAMS_CONVERSATION_QWENPAW_AGENT_ID:default}") String agentId,
            @Value("${AGENTTEAMS_CONVERSATION_QWENPAW_AUTHORIZATION_TOKEN:}") String authorizationToken,
            @Value("${AGENTTEAMS_CONVERSATION_CONNECT_TIMEOUT_MS:10000}") long connectTimeoutMillis,
            @Value("${AGENTTEAMS_CONVERSATION_REQUEST_TIMEOUT_MS:120000}") long requestTimeoutMillis,
            @Value("${AGENTTEAMS_CONVERSATION_MAX_RESPONSE_BYTES:4194304}") long maxResponseBytes,
            @Value("${AGENTTEAMS_CONVERSATION_USER_ID:agentteams}") String userId,
            @Value("${AGENTTEAMS_CONVERSATION_CHANNEL:console}") String channel,
            @Value("${AGENTTEAMS_CONVERSATION_MAX_CONCURRENT_REQUESTS:128}") int maxConcurrentRequests,
            @Value("${AGENTTEAMS_CONVERSATION_MAX_EVENTS_PER_SESSION:10000}") int maxEventsPerSession,
            @Value("${AGENTTEAMS_CONVERSATION_MAX_SESSIONS:10000}") int maxSessions) {
        return new ConversationRuntimeConfiguration(URI.create(endpoint), agentId, authorizationToken,
                Duration.ofMillis(connectTimeoutMillis), Duration.ofMillis(requestTimeoutMillis),
                maxResponseBytes, userId, channel, maxConcurrentRequests, maxEventsPerSession, maxSessions);
    }

    @Bean(destroyMethod = "close")
    ConversationRuntimePort conversationRuntime(
            @Value("${AGENTTEAMS_CONVERSATION_RUNTIME:fake}") String runtimeName,
            ConversationRuntimeConfiguration configuration) {
        if ("fake".equalsIgnoreCase(runtimeName)) {
            return new FakeConversationRuntime();
        }
        if ("qwenpaw".equalsIgnoreCase(runtimeName)) {
            return new QwenPawConversationRuntime(configuration);
        }
        throw new IllegalArgumentException("unsupported conversation runtime: " + runtimeName);
    }

    @Bean
    ConversationRepository conversationRepository(JdbcTemplate jdbc) {
        return new JdbcConversationRepository(jdbc);
    }

    @Bean(destroyMethod = "close")
    ConversationService conversationService(ConversationRuntimePort runtime, ConversationRepository repository) {
        return new ConversationService(runtime, repository);
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
