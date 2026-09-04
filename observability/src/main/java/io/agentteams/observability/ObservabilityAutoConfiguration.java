package io.agentteams.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Wires the shared metrics, tracing and correlation-id infrastructure for any application that
 * depends on this module; the beans used to live in ControlPlaneConfiguration and moved here so
 * the observability kernel can be reused without the control plane.
 */
@AutoConfiguration
public class ObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ControlPlaneMetrics.class)
    ControlPlaneMetrics controlPlaneMetrics(ObjectProvider<MeterRegistry> registries) {
        return new ControlPlaneMetrics(registries.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Bean
    @Primary
    TaskMetricsPort taskMetricsPort(ObjectProvider<ControlPlaneMetrics> metrics) {
        ControlPlaneMetrics available = metrics.getIfAvailable();
        return available == null ? TaskMetricsPort.noop() : available;
    }

    @Bean
    @ConditionalOnMissingBean
    CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }
}
