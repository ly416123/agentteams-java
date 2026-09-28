package io.agentteams.controlplane.evaluation;

import io.agentteams.controlplane.task.TaskDecisionRecordRepository;
import io.agentteams.controlplane.task.TaskProcessEventRepository;
import io.agentteams.controlplane.task.TaskResultManifestRepository;
import io.agentteams.controlplane.task.TaskRunObservationRepository;
import io.agentteams.controlplane.task.TaskTreeRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class EvaluationConfiguration {
    @Bean
    EvidenceBundleAssembler evidenceBundleAssembler(TaskRunObservationRepository runs,
            TaskProcessEventRepository events, TaskTreeRepository tree, TaskDecisionRecordRepository decisions,
            TaskResultManifestRepository manifests) {
        return new EvidenceBundleAssembler(runs, events, tree, decisions, manifests);
    }

    @Bean
    EvaluationService evaluationService(EvaluationRepository repository, EvidenceBundleAssembler assembler, Clock clock) {
        return new EvaluationService(repository, assembler, clock);
    }
}
