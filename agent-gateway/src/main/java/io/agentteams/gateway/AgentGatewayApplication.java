package io.agentteams.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Bootstrap for the Agent Gateway process. Infrastructure ports are supplied by deployment wiring. */
@SpringBootApplication
public class AgentGatewayApplication {

    private AgentGatewayApplication() {
    }

    public static void main(String[] args) {
        SpringApplication.run(AgentGatewayApplication.class, args);
    }
}
