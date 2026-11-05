package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class JdbcPersistenceWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AgentGatewayGrpcConfiguration.class)
            .withPropertyValues("agentteams.gateway.grpc.port=0");

    @Test
    void keepsNoopPortsWhenDataSourceIsAbsent() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(CommandReplayPort.class);
            assertThat(context).hasSingleBean(InboundEventPort.class);
            assertThat(context).hasSingleBean(AgentStatePort.class);
            assertThat(context).doesNotHaveBean(JdbcCommandEventStore.class);
            assertThat(context).doesNotHaveBean(JdbcInboundEventStore.class);
            assertThat(context).doesNotHaveBean(JdbcAgentStateStore.class);
        });
    }

    @Test
    void usesEachJdbcPortOnceWhenDataSourceIsPresent() {
        new ApplicationContextRunner()
                .withUserConfiguration(MockDataSourceConfiguration.class, AgentGatewayGrpcConfiguration.class)
                .withPropertyValues("agentteams.gateway.grpc.port=0",
                        "spring.datasource.url=jdbc:postgresql://localhost/agentteams")
                .run(context -> {
                    assertThat(context).hasSingleBean(JdbcCommandEventStore.class);
                    assertThat(context).hasSingleBean(JdbcInboundEventStore.class);
                    assertThat(context).hasSingleBean(JdbcAgentStateStore.class);
                    assertThat(context.getBeansOfType(CommandReplayPort.class)).hasSize(1);
                    assertThat(context.getBeansOfType(InboundEventPort.class)).hasSize(1);
                    assertThat(context.getBeansOfType(AgentStatePort.class)).hasSize(1);
                    assertThat(context.getBean(CommandReplayPort.class)).isInstanceOf(JdbcCommandEventStore.class);
                    assertThat(context.getBean(InboundEventPort.class)).isInstanceOf(JdbcInboundEventStore.class);
                    assertThat(context.getBean(AgentStatePort.class)).isInstanceOf(JdbcAgentStateStore.class);
                    assertThat(context).hasSingleBean(AgentSessionStore.class);
                    assertThat(context.getBean(AgentSessionStore.class)).isInstanceOf(JdbcAgentSessionStore.class);
                    assertThat(context.getBean(AuthenticationPort.class)).isInstanceOf(SessionTokenAuthenticator.class);
                });
    }

    @Test
    void rejectsAuthenticationWhenDataSourceExistsWithoutSessionStore() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("dataSource", mock(DataSource.class));
        AgentGatewayGrpcConfiguration configuration = new AgentGatewayGrpcConfiguration();

        AuthenticationPort authentication = configuration.authenticationPort(
                beanFactory.getBeanProvider(AgentSessionStore.class),
                beanFactory.getBeanProvider(DataSource.class), Clock.systemUTC());

        assertThat(authentication.authenticate(null, null).accepted()).isFalse();
    }

    @Configuration(proxyBeanMethods = false)
    static class MockDataSourceConfiguration {
        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }
    }
}
