package io.agentteams.observability;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.slf4j.helpers.BasicMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.servlet.ServletContextInitializerBeans;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.mockito.Mockito;

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
    void registersAndExecutesCorrelationIdFilterThroughSpringBootServletModel() throws Exception {
        context.run(ctx -> {
            RecordingServletContext servletContext = registerServletInitializers(ctx);
            assertThat(servletContext.registeredFilters()).containsOnlyKeys("correlationIdFilter");

            Filter filter = servletContext.registeredFilters().get("correlationIdFilter");
            assertThat(filter).isSameAs(ctx.getBean(CorrelationIdFilter.class));

            withBasicMdcAdapter(() -> {
                executeFilter(filter, servletContext, "client-123");
                executeFilter(filter, servletContext, "not a valid correlation id");
            });
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
    void backsOffWhenCorrelationIdFilterIsWrappedInFilterRegistrationBean() {
        context.withUserConfiguration(CustomCorrelationFilterRegistration.class).run(ctx -> {
            assertThat(ctx).doesNotHaveBean("correlationIdFilter");
            RecordingServletContext servletContext = registerServletInitializers(ctx);
            assertThat(servletContext.registeredFilters()).containsOnlyKeys("customCorrelationIdFilter");
        });
    }

    @Test
    void keepsDefaultCorrelationIdFilterForUnrelatedFilterRegistration() {
        context.withUserConfiguration(UnrelatedFilterRegistration.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(CorrelationIdFilter.class);
            RecordingServletContext servletContext = registerServletInitializers(ctx);
            assertThat(servletContext.registeredFilters()).containsOnlyKeys("correlationIdFilter",
                    "unrelatedFilter");
        });
    }

    @Test
    void userDefinedControlPlaneMetricsWinsOverTheDefault() {
        ControlPlaneMetrics custom = new ControlPlaneMetrics(new SimpleMeterRegistry());
        context.withBean("userMetrics", ControlPlaneMetrics.class, () -> custom).run(ctx -> {
            assertThat(ctx.getBean(ControlPlaneMetrics.class)).isSameAs(custom);
            assertThat(ctx.getBean(TaskMetricsPort.class)).isSameAs(custom);
        });
    }

    private static RecordingServletContext registerServletInitializers(
            org.springframework.context.ConfigurableApplicationContext context) throws Exception {
        RecordingServletContext servletContext = new RecordingServletContext();
        for (ServletContextInitializer initializer : new ServletContextInitializerBeans(context)) {
            initializer.onStartup(servletContext);
        }
        return servletContext;
    }

    private static void executeFilter(Filter filter, MockServletContext servletContext, String suppliedId)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
        request.addHeader(CorrelationIdFilter.HEADER, suppliedId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcValueInChain = new AtomicReference<>();
        filter.doFilter(request, response, (requestInChain, responseInChain) -> {
            mdcValueInChain.set(MDC.get("correlationId"));
        });

        String responseId = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(mdcValueInChain).hasValue(responseId);
        if ("client-123".equals(suppliedId)) {
            assertThat(responseId).isEqualTo(suppliedId);
        } else {
            assertThat(responseId).isNotEqualTo(suppliedId);
            assertThat(java.util.UUID.fromString(responseId)).isNotNull();
        }
        assertThat(MDC.get("correlationId")).isNull();
    }

    private static void withBasicMdcAdapter(ThrowingRunnable action) throws Exception {
        Method setMdcAdapter = MDC.class.getDeclaredMethod("setMDCAdapter", MDCAdapter.class);
        setMdcAdapter.setAccessible(true);
        MDCAdapter previousAdapter = MDC.getMDCAdapter();
        Map<String, String> previousContext = MDC.getCopyOfContextMap();
        setMdcAdapter.invoke(null, new BasicMDCAdapter());
        try {
            action.run();
        } finally {
            MDC.clear();
            setMdcAdapter.invoke(null, previousAdapter);
            if (previousContext != null) {
                MDC.setContextMap(previousContext);
            }
        }
    }

    private static final class RecordingServletContext extends MockServletContext {
        private final Map<String, Filter> registeredFilters = new LinkedHashMap<>();

        @Override
        public FilterRegistration.Dynamic addFilter(String filterName, Filter filter) {
            registeredFilters.put(filterName, filter);
            FilterRegistration.Dynamic registration = Mockito.mock(FilterRegistration.Dynamic.class);
            Mockito.when(registration.getName()).thenReturn(filterName);
            addFilterRegistration(registration);
            return registration;
        }

        private Map<String, Filter> registeredFilters() {
            return registeredFilters;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomCorrelationFilterRegistration {
        @Bean
        FilterRegistrationBean<CorrelationIdFilter> customCorrelationIdFilter() {
            FilterRegistrationBean<CorrelationIdFilter> registration =
                    new FilterRegistrationBean<>(new CorrelationIdFilter());
            registration.setName("customCorrelationIdFilter");
            return registration;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UnrelatedFilterRegistration {
        @Bean
        FilterRegistrationBean<Filter> unrelatedFilter() {
            Filter filter = (request, response, chain) -> chain.doFilter(request, response);
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
            registration.setName("unrelatedFilter");
            return registration;
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
