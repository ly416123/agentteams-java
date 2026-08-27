package io.agentteams.gateway;

import io.agentteams.contracts.v1.ProtocolVersion;
import io.agentteams.contracts.v1.ServerMessage;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentteams.application.api.ExecutionEventPort;
import io.agentteams.application.api.ConfigEventPort;
import io.agentteams.application.api.QuotaReservationPort;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.Nats;
import java.time.Clock;
import java.time.Instant;
import java.io.IOException;
import java.net.http.HttpClient;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.ObjectProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;

/** Default process wiring; production deployments can replace each port adapter with a durable bean. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({GrpcServerProperties.class, GrpcTlsProperties.class, NatsGatewayProperties.class,
        GatewayQuotaProperties.class, GatewayOperationProperties.class})
public class AgentGatewayGrpcConfiguration {

    @Bean
    @ConditionalOnProperty(name = "agentteams.gateway.worker-operations.remote-enabled", havingValue = "true")
    @ConditionalOnMissingBean(GatewayWorkerOperationObservationReporter.class)
    public GatewayWorkerOperationObservationReporter gatewayWorkerOperationObservationReporter(
            GatewayOperationProperties properties, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new HttpGatewayWorkerOperationObservationReporter(HttpClient.newBuilder()
                .connectTimeout(properties.getRequestTimeout()).build(), objectMapper, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "agentteams.gateway.quota.remote-enabled", havingValue = "true")
    @ConditionalOnMissingBean(QuotaReservationPort.class)
    public QuotaReservationPort controlPlaneQuotaReservationPort(GatewayQuotaProperties properties,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new ControlPlaneQuotaReservationClient(HttpClient.newBuilder()
                .connectTimeout(properties.getRequestTimeout())
                .build(), objectMapper, properties);
    }

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock gatewayClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(com.fasterxml.jackson.databind.ObjectMapper.class)
    public com.fasterxml.jackson.databind.ObjectMapper gatewayObjectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Bean
    public GatewayMetrics gatewayMetrics(ObjectProvider<MeterRegistry> registries) {
        return new GatewayMetrics(registries.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Bean
    @Primary
    public GatewayMetricsPort gatewayMetricsPort(ObjectProvider<GatewayMetrics> metrics) {
        GatewayMetrics available = metrics.getIfAvailable();
        return available == null ? GatewayMetricsPort.noop() : available;
    }

    @Bean
    @ConditionalOnMissingBean(ConnectionRegistry.class)
    public ConnectionRegistry connectionRegistry(GatewayMetricsPort metrics) {
        return new ConnectionRegistry(ConnectionTermination.grpcStream(), metrics);
    }

    @Bean
    @ConditionalOnMissingBean(AgentStatePort.class)
    public AgentStatePort agentStatePort(ObjectProvider<DataSource> dataSources,
            ObjectProvider<GatewayWorkerOperationObservationReporter> operationObservations) {
        DataSource dataSource = dataSources.getIfAvailable();
        return dataSource == null ? new NoopAgentStatePort()
                : new JdbcAgentStateStore(dataSource,
                        operationObservations.getIfAvailable(GatewayWorkerOperationObservationReporter::noop));
    }

    @Bean
    @ConditionalOnMissingBean({AuthenticationPort.class, AgentSessionStore.class})
    public AuthenticationPort authenticationPort() {
        return (connection, hello) -> AuthenticationPort.AuthenticationDecision.allow();
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(AgentSessionStore.class)
    public AgentSessionStore jdbcAgentSessionStore(DataSource dataSource) {
        return new JdbcAgentSessionStore(new org.springframework.jdbc.core.JdbcTemplate(dataSource));
    }

    @Bean
    @ConditionalOnBean(AgentSessionStore.class)
    @ConditionalOnMissingBean(AuthenticationPort.class)
    public AuthenticationPort sessionTokenAuthentication(AgentSessionStore sessions, Clock clock) {
        return new SessionTokenAuthenticator(sessions, clock);
    }

    @Bean
    @ConditionalOnMissingBean(ProtocolNegotiationPort.class)
    public ProtocolNegotiationPort protocolNegotiationPort() {
        return ProtocolNegotiationPort.compatiblePeerVersion();
    }

    @Bean
    @ConditionalOnMissingBean(CommandReplayPort.class)
    public CommandReplayPort commandReplayPort(ObjectProvider<DataSource> dataSources,
            GatewayMetricsPort metrics) {
        DataSource dataSource = dataSources.getIfAvailable();
        return dataSource == null ? new NoopCommandReplayPort() : new JdbcCommandEventStore(dataSource, metrics);
    }

    @Bean
    @ConditionalOnMissingBean(CommandDeliveryService.class)
    public CommandDeliveryService commandDeliveryService(ConnectionRegistry registry,
            CommandReplayPort commands, Clock clock, GatewayMetricsPort metrics) {
        return new CommandDeliveryService(registry, commands, clock, metrics);
    }

    @Bean
    @ConditionalOnMissingBean(TaskAssignedCommandHandler.class)
    public TaskAssignedCommandHandler taskAssignedCommandHandler(CommandDeliveryService delivery) {
        return new TaskAssignedCommandHandler(delivery);
    }

    @Bean
    @ConditionalOnMissingBean(ConfigChangedCommandHandler.class)
    public ConfigChangedCommandHandler configChangedCommandHandler(CommandDeliveryService delivery,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new ConfigChangedCommandHandler(delivery, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "agentteams.gateway.nats.enabled", havingValue = "true")
    @ConditionalOnMissingBean(ConfigEventPort.class)
    public ConfigEventPort natsConfigEventPort(JetStream gatewayJetStream,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new NatsConfigEventPublisher(gatewayJetStream, objectMapper);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "agentteams.gateway.nats.enabled", havingValue = "true")
    public Connection gatewayNatsConnection(NatsGatewayProperties properties)
            throws IOException, InterruptedException {
        return Nats.connect(properties.getUrl());
    }

    @Bean
    @ConditionalOnProperty(name = "agentteams.gateway.nats.enabled", havingValue = "true")
    public JetStream gatewayJetStream(Connection gatewayNatsConnection) throws IOException {
        return gatewayNatsConnection.jetStream();
    }

    @Bean
    @ConditionalOnProperty(name = "agentteams.gateway.nats.enabled", havingValue = "true")
    public ExecutionEventPort natsExecutionEventPort(JetStream gatewayJetStream,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new NatsExecutionEventPublisher(gatewayJetStream, objectMapper);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnProperty(name = "agentteams.gateway.nats.enabled", havingValue = "true")
    public NatsGatewayEventConsumer natsGatewayEventConsumer(Connection gatewayNatsConnection,
            TaskAssignedCommandHandler commandHandler, ConfigChangedCommandHandler configHandler,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            NatsGatewayProperties properties, GatewayMetricsPort metrics, ObjectProvider<Tracer> tracers,
            ObjectProvider<Propagator> propagators) throws IOException {
        return new NatsGatewayEventConsumer(gatewayNatsConnection, commandHandler, configHandler, objectMapper,
                properties.getSubject(), properties.taskConsumerDurable(), properties.getConfigSubject(),
                properties.configConsumerDurable(), metrics, new AsyncConsumerTracing(
                        tracers.getIfAvailable(() -> Tracer.NOOP), tracingPropagator(propagators)));
    }

    @Bean
    @ConditionalOnMissingBean(InboundEventPort.class)
    public InboundEventPort inboundEventPort(ObjectProvider<DataSource> dataSources) {
        DataSource dataSource = dataSources.getIfAvailable();
        return dataSource == null ? new NoopInboundEventPort() : new JdbcInboundEventStore(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean({GatewayApplicationHandler.class, ExecutionEventPort.class})
    public GatewayApplicationHandler gatewayApplicationHandler() {
        return new NoopGatewayApplicationHandler();
    }

    @Bean
    @ConditionalOnBean(ExecutionEventPort.class)
    @ConditionalOnMissingBean(GatewayApplicationHandler.class)
    public GatewayApplicationHandler controlPlaneGatewayApplicationHandler(
            ExecutionEventPort executionEvents, ObjectProvider<ConfigEventPort> configEvents, Clock clock) {
        ConfigEventPort available = configEvents.getIfAvailable(() -> command -> { });
        return new ControlPlaneGatewayApplicationHandler(executionEvents, available, clock);
    }

    @Bean
    @ConditionalOnMissingBean(InboundEventHandler.class)
    public InboundEventHandler inboundEventHandler(ConnectionRegistry registry, InboundEventPort events,
            GatewayApplicationHandler application, CommandDeliveryService delivery, Clock clock) {
        return new InboundEventHandler(registry, events, application, delivery, clock);
    }

    @Bean
    @ConditionalOnMissingBean(AgentChannelService.class)
    public AgentChannelService agentChannelService(ConnectionRegistry registry, AgentStatePort state,
            AuthenticationPort authentication, ProtocolNegotiationPort negotiation,
            CommandDeliveryService delivery, InboundEventHandler inbound, Clock clock, GatewayMetricsPort metrics) {
        return new AgentChannelService(
                ProtocolVersion.newBuilder().setMajor(2).setMinor(3).build(),
                registry, state, authentication, GrpcTransportIdentity::current, negotiation, delivery, inbound, clock,
                metrics);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public AgentGatewayGrpcServer agentGatewayGrpcServer(GrpcServerProperties properties,
            GrpcTlsProperties tlsProperties, AgentChannelService channelService, ObjectProvider<Tracer> tracers,
            ObjectProvider<Propagator> propagators, ObjectProvider<QuotaServiceHandler> quotaService) {
        return new AgentGatewayGrpcServer(properties.getPort(), properties.getShutdownTimeout(), channelService,
                tlsProperties, new GrpcServerTracingInterceptor(
                        tracers.getIfAvailable(() -> Tracer.NOOP), tracingPropagator(propagators)),
                quotaService.getIfAvailable());
    }

    @Bean
    @ConditionalOnBean(QuotaReservationPort.class)
    @ConditionalOnMissingBean(QuotaServiceHandler.class)
    public QuotaServiceHandler quotaServiceHandler(QuotaReservationPort reservations) {
        return new QuotaServiceHandler(reservations);
    }

    private static Propagator tracingPropagator(ObjectProvider<Propagator> propagators) {
        return propagators.getIfAvailable(() -> {
            var openTelemetry = GlobalOpenTelemetry.get();
            var w3c = TextMapPropagator.composite(W3CTraceContextPropagator.getInstance(),
                    W3CBaggagePropagator.getInstance());
            return new OtelPropagator(ContextPropagators.create(w3c),
                    openTelemetry.getTracerProvider().get("agentteams-agent-gateway"));
        });
    }

    private static final class NoopAgentStatePort implements AgentStatePort {
        @Override
        public void registered(ConnectionRegistry.ConnectionSnapshot connection, Instant at) {
        }

        @Override
        public boolean seen(ConnectionRegistry.ConnectionSnapshot connection, Instant at) {
            return true;
        }

        @Override
        public boolean disconnected(ConnectionRegistry.ConnectionSnapshot connection, Instant at) {
            return true;
        }
    }

    private static final class NoopInboundEventPort implements InboundEventPort {
        @Override
        public boolean recordIfNew(String eventId, String agentId, UUID connectionId, Instant receivedAt) {
            return true;
        }
    }

    private static final class NoopGatewayApplicationHandler implements GatewayApplicationHandler {
        @Override
        public void taskAccepted(ConnectionRegistry.ConnectionSnapshot connection,
                io.agentteams.contracts.v1.TaskAccepted event) {
        }

        @Override
        public void taskProgress(ConnectionRegistry.ConnectionSnapshot connection,
                io.agentteams.contracts.v1.TaskProgress event) {
        }

        @Override
        public void taskHeartbeat(ConnectionRegistry.ConnectionSnapshot connection,
                io.agentteams.contracts.v1.TaskHeartbeat event) {
        }

        @Override
        public void taskCompleted(ConnectionRegistry.ConnectionSnapshot connection,
                io.agentteams.contracts.v1.TaskCompleted event) {
        }

        @Override
        public void taskFailed(ConnectionRegistry.ConnectionSnapshot connection,
                io.agentteams.contracts.v1.TaskFailed event) {
        }
    }

    private static final class NoopCommandReplayPort implements CommandReplayPort {
        @Override
        public SequencedCommand append(String agentId, ServerMessage command) {
            if (!command.hasTaskAssigned() && !command.hasConfigChanged()) {
                throw new IllegalArgumentException("unsupported Agent command payload");
            }
            long sequence = command.hasTaskAssigned()
                    ? command.getTaskAssigned().getMetadata().getSequence()
                    : command.getConfigChanged().getMetadata().getSequence();
            if (sequence <= 0) {
                sequence = 1;
            }
            return new SequencedCommand(sequence, command);
        }

        @Override
        public List<SequencedCommand> replayUnacknowledged(String agentId) {
            return List.of();
        }

        @Override
        public void markDelivered(String agentId, UUID connectionId, long sequence) {
        }

        @Override
        public AcknowledgementValidation validateAcknowledgement(String agentId, UUID connectionId,
                long sequence) {
            return AcknowledgementValidation.rejected(0, "noop command store has no durable delivery");
        }

        @Override
        public void acknowledge(String agentId, long sequence) {
        }
    }
}
