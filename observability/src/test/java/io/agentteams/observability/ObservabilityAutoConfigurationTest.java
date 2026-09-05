package io.agentteams.observability;

import jakarta.servlet.Filter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The auto-configuration must keep the wiring contract that ControlPlaneConfiguration used to
 * provide: one shared metrics facade, a primary TaskMetricsPort delegating to it, and the
 * correlation filter; user-provided beans must still win over the defaults.
 */
class ObservabilityAutoConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class));

    @Test
    void registersMetricsTaskMetricsPortAndCorrelationFilter() {
        context.run(ctx -> {
            // ControlPlaneMetrics itself implements TaskMetricsPort, so both bean names match that
            // type; the primary adapter must delegate to the single metrics facade instance.
            assertThat(ctx).hasBean("controlPlaneMetrics").hasBean("taskMetricsPort")
                    .hasSingleBean(CorrelationIdFilter.class);
            assertThat(ctx.getBean("taskMetricsPort", TaskMetricsPort.class))
                    .isSameAs(ctx.getBean("controlPlaneMetrics", ControlPlaneMetrics.class));
        });
    }

    @Test
    void registersExactlyOneCorrelationIdFilterThatIsServletFilterCompatible() {
        context.run(ctx -> {
            assertThat(ctx.getBeansOfType(CorrelationIdFilter.class))
                    .containsOnlyKeys("correlationIdFilter");
            assertThat(ctx.getBean(CorrelationIdFilter.class)).isInstanceOf(Filter.class);
        });
    }

    @Test
    void backsOffWhenApplicationProvidesCorrelationIdFilter() {
        CorrelationIdFilter custom = new CorrelationIdFilter();
        context.withBean("customCorrelationIdFilter", CorrelationIdFilter.class, () -> custom)
                .run(ctx -> assertThat(ctx.getBeansOfType(CorrelationIdFilter.class))
                        .containsOnlyKeys("customCorrelationIdFilter")
                        .containsEntry("customCorrelationIdFilter", custom));
    }

    @Test
    void userDefinedControlPlaneMetricsWinsOverTheDefault() {
        ControlPlaneMetrics custom = new ControlPlaneMetrics(new SimpleMeterRegistry());
        context.withBean("userMetrics", ControlPlaneMetrics.class, () -> custom).run(ctx -> {
            assertThat(ctx.getBean(ControlPlaneMetrics.class)).isSameAs(custom);
            assertThat(ctx.getBean(TaskMetricsPort.class)).isSameAs(custom);
        });
    }
}
