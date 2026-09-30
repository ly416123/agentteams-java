package io.agentteams.controlplane.matrix;

import io.agentteams.controlplane.channel.MatrixChannelAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import io.agentteams.application.api.TaskCommandPort;
import io.agentteams.controlplane.service.TaskService;
import io.agentteams.controlplane.command.CommandDispatcher;
import io.agentteams.controlplane.security.ExecutionContextResolver;
import java.time.Clock;
import java.time.Duration;
import java.net.URI;
import java.net.http.HttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({MatrixAppServiceProperties.class, MatrixDeliveryProperties.class})
public class MatrixAppServiceConfiguration {
    @Bean
    MatrixInboxRepository matrixInboxRepository(JdbcTemplate jdbcTemplate) {
        return new MatrixInboxRepository(jdbcTemplate);
    }

    @Bean
    MatrixAppService matrixAppService(MatrixInboxRepository inbox) {
        return new MatrixAppService(inbox);
    }

    @Bean
    MatrixIdentityBinder matrixIdentityBinder(JdbcTemplate jdbcTemplate) {
        return new JdbcMatrixIdentityBinder(jdbcTemplate);
    }

    @Bean
    MatrixOutboundRepository matrixOutboundRepository(JdbcTemplate jdbcTemplate) {
        return new MatrixOutboundRepository(jdbcTemplate);
    }

    @Bean
    MatrixChannelBindingRepository matrixChannelBindingRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcMatrixChannelBindingRepository(jdbcTemplate);
    }

    @Bean
    MatrixRoomBindingRepository matrixRoomBindingRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcMatrixRoomBindingRepository(jdbcTemplate);
    }

    @Bean
    MatrixRoomBindingService matrixRoomBindingService(MatrixRoomBindingRepository repository) {
        return new MatrixRoomBindingService(repository);
    }

    @Bean
    MatrixChannelAdapter matrixChannelAdapter(MatrixChannelBindingRepository bindings,
            MatrixOutboundRepository outbound, MatrixRoomBindingRepository roomBindings, Clock clock) {
        return new MatrixChannelAdapter(bindings, outbound, roomBindings, clock);
    }

    @Bean
    MatrixEventProjector matrixEventProjector(MatrixRoomBindingRepository bindings,
            MatrixOutboundRepository outbound, Clock clock) {
        return new MatrixEventProjector(bindings, outbound, clock);
    }

    @Bean
    MatrixDeliveryService matrixDeliveryService(MatrixOutboundRepository outbound, MatrixRoomBindingRepository bindings,
            Clock clock, PlatformTransactionManager transactionManager) {
        return new MatrixDeliveryService(outbound, clock, Duration.ofSeconds(5), bindings,
                new TransactionTemplate(transactionManager));
    }

    @Bean
    @ConditionalOnProperty(prefix = "agentteams.matrix.delivery", name = "enabled", havingValue = "true")
    MatrixDeliveryService.MatrixTransport matrixHomeserverTransport(MatrixDeliveryProperties properties,
            ObjectMapper mapper) {
        if (properties.getRequestTimeout() == null || properties.getRequestTimeout().isZero()
                || properties.getRequestTimeout().isNegative()
                || properties.getRequestTimeout().compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("Matrix request timeout must be positive and at most 2 minutes");
        }
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.getRequestTimeout()).build();
        return new MatrixHomeserverTransport(client, URI.create(properties.getHomeserverUrl()),
                properties.getAccessToken(), properties.getRequestTimeout(), properties.getMaxResponseBytes(), mapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "agentteams.matrix.delivery", name = "enabled", havingValue = "true")
    MatrixDeliveryScheduler matrixDeliveryScheduler(MatrixDeliveryService delivery,
            MatrixDeliveryService.MatrixTransport transport, MatrixDeliveryProperties properties) {
        if (properties.getBatchSize() < 1 || properties.getPollInterval() == null
                || properties.getPollInterval().isZero() || properties.getPollInterval().isNegative()) {
            throw new IllegalArgumentException("Matrix delivery batch size and poll interval must be positive");
        }
        return new MatrixDeliveryScheduler(delivery, transport, properties.getBatchSize());
    }

    @Bean
    @ConditionalOnBean(TaskCommandPort.class)
    @ConditionalOnMissingBean(MatrixCommandHandler.class)
    MatrixCommandHandler matrixCommandHandler(TaskCommandPort tasks, TaskService taskService,
            ObjectProvider<CommandDispatcher> dispatcher, ObjectProvider<ExecutionContextResolver> contexts) {
        CommandDispatcher unified = dispatcher.getIfAvailable();
        ExecutionContextResolver resolver = contexts.getIfAvailable();
        if (unified != null && resolver != null) {
            return new MatrixTaskCommandHandler(unified, resolver, taskService);
        }
        return new MatrixTaskCommandHandler(tasks, taskService);
    }
}
