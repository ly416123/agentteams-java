package io.agentteams.controlplane.matrix;

import io.agentteams.controlplane.channel.MatrixChannelAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import io.agentteams.application.api.TaskCommandPort;
import io.agentteams.controlplane.service.TaskService;
import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MatrixAppServiceProperties.class)
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
    MatrixChannelAdapter matrixChannelAdapter(MatrixChannelBindingRepository bindings,
            MatrixOutboundRepository outbound, Clock clock) {
        return new MatrixChannelAdapter(bindings, outbound, clock);
    }

    @Bean
    MatrixEventProjector matrixEventProjector(MatrixOutboundRepository outbound, Clock clock) {
        return new MatrixEventProjector(outbound, clock);
    }

    @Bean
    MatrixDeliveryService matrixDeliveryService(MatrixOutboundRepository outbound, Clock clock) {
        return new MatrixDeliveryService(outbound, clock, Duration.ofSeconds(5));
    }

    @Bean
    @ConditionalOnBean(TaskCommandPort.class)
    @ConditionalOnMissingBean(MatrixCommandHandler.class)
    MatrixCommandHandler matrixCommandHandler(TaskCommandPort tasks, TaskService taskService) {
        return new MatrixTaskCommandHandler(tasks, taskService);
    }
}
