package io.agentteams.manager;

@FunctionalInterface
public interface ModelCallAuditor {
    void record(ModelCallAudit audit);

    static ModelCallAuditor noop() { return audit -> { }; }
}
