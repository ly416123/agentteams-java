package io.agentteams.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.manager.session.JdbcManagerSessionRepository;
import io.agentteams.manager.session.ManagerSessionRepository;
import io.agentteams.manager.session.ManagerSessionServiceFacade;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableTransactionManagement
public class ManagerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ManagerApplication.class, args);
    }

    @Bean
    ManagerSessionRepository managerSessionRepository(JdbcTemplate jdbc) {
        return new JdbcManagerSessionRepository(jdbc);
    }

    @Bean
    ModelProvider managerModelProvider() {
        return DeepSeekConfiguration.fromEnvironment(System.getenv())
                .createProvider(HttpClient.newHttpClient(), new ObjectMapper());
    }

    @Bean
    io.agentteams.application.api.TaskCommandPort taskCommandPort(
            @Value("${AGENTTEAMS_CONTROL_PLANE_URL:}") String controlPlaneUrl,
            ObjectMapper mapper) {
        return new HttpTaskCommandPort(controlPlaneUrl, HttpClient.newHttpClient(), mapper);
    }

    @Bean
    ManagerToolRegistry managerToolRegistry(io.agentteams.application.api.TaskCommandPort taskCommands,
            ObjectMapper mapper) {
        ControlPlaneCreateTaskTool createTask = new ControlPlaneCreateTaskTool(taskCommands, mapper);
        return new ManagerToolRegistry(Map.of("create_task", new ManagerToolRegistry.Tool(
                "task:create", false, input -> createTask.create((CreateTaskIntent) input))));
    }

    @Bean
    ManagerSessionService managerSessionService(ModelProvider provider, ObjectMapper mapper,
            ManagerToolRegistry tools) {
        return new ManagerSessionService(provider, mapper, tools);
    }

    @Bean
    ManagerSessionServiceFacade managerSessionServiceFacade(ManagerSessionRepository repository,
            ManagerSessionService modelService) {
        return new ManagerSessionServiceFacade(repository, modelService, Clock.systemUTC());
    }
}
