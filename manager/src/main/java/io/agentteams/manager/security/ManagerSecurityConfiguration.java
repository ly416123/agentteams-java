package io.agentteams.manager.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
@EnableConfigurationProperties(ManagerSecurityProperties.class)
public class ManagerSecurityConfiguration {
    @Bean
    ManagerIdentityTokenValidator managerIdentityTokenValidator(ManagerSecurityProperties properties) {
        return OidcManagerIdentityTokenValidator.fromProperties(properties);
    }

    @Bean
    ManagerAuthenticationFilter managerAuthenticationFilter(ManagerIdentityTokenValidator validator,
            JdbcTemplate jdbc) {
        return new ManagerAuthenticationFilter(validator, new ManagerProjectScopeResolver(jdbc));
    }

    @Bean
    SecurityFilterChain managerSecurity(HttpSecurity http, ManagerAuthenticationFilter filter) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().permitAll())
                .addFilterBefore(filter, AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
