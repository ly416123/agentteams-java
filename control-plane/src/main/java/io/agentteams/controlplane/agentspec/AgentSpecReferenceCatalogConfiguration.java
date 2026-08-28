package io.agentteams.controlplane.agentspec;

import io.agentteams.controlplane.mcp.McpServerService;
import io.agentteams.controlplane.service.ModelCatalogService;
import io.agentteams.controlplane.skill.SkillService;
import io.agentteams.controlplane.skill.SkillPackageStorageService;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires AgentSpec publication validation to the real model, skill, and MCP services. */
@Configuration(proxyBeanMethods = false)
public class AgentSpecReferenceCatalogConfiguration {

    @Bean
    AgentSpecReferenceValidator agentSpecReferenceValidator(ModelCatalogService models,
            SkillService skills, McpServerService mcp, JdbcTemplate jdbc,
            ObjectProvider<SkillPackageStorageService> packageStorage) {
        AgentSpecReferenceVisibility visibility = new JdbcAgentSpecReferenceVisibility(jdbc);
        return new CatalogAgentSpecReferenceValidator(
                new AgentSpecModelServiceReferenceCatalogAdapter(models, visibility),
                new AgentSpecSkillServiceReferenceCatalogAdapter(skills, visibility,
                        packageStorage.getIfAvailable(), Duration.ofMinutes(15)),
                new AgentSpecMcpServiceReferenceCatalogAdapter(mcp, visibility));
    }
}
