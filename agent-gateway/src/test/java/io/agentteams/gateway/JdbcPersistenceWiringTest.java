package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
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
                .withPropertyValues("agentteams.gateway.grpc.port=0")
                .run(context -> {
                    assertThat(context).hasSingleBean(JdbcCommandEventStore.class);
                    assertThat(context).hasSingleBean(JdbcInboundEventStore.class);
                    assertThat(context).hasSingleBean(JdbcAgentStateStore.class);
                    assertThat(context.getBeansOfType(CommandReplayPort.class)).hasSize(1);
                    assertThat(context.getBeansOfType(InboundEventPort.class)).hasSize(1);
                    assertThat(context.getBeansOfType(AgentStatePort.class)).hasSize(1);
                    assertThat(context).doesNotHaveBean("commandReplayPort");
                    assertThat(context).doesNotHaveBean("inboundEventPort");
                    assertThat(context).doesNotHaveBean("agentStatePort");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class MockDataSourceConfiguration {
        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }
    }
}
