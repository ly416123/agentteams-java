package io.agentteams.observability;

import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * Servlet-only part of observability. Keeping this separate prevents applications that only use
 * the metrics API from needing to resolve the provided Servlet API at configuration load time.
 */
@AutoConfiguration
@ConditionalOnClass(Filter.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ServletObservabilityAutoConfiguration {

    @Bean
    // A FilterRegistrationBean<CorrelationIdFilter> is the supported custom registration path.
    // Unrelated registration beans, such as authentication filters, must not suppress this default.
    @ConditionalOnMissingBean(value = CorrelationIdFilter.class,
            parameterizedContainer = FilterRegistrationBean.class)
    CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }
}
