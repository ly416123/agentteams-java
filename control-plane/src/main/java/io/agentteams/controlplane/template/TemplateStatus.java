package io.agentteams.controlplane.template;

/** Lifecycle states shared by template revisions. */
public enum TemplateStatus {
    DRAFT,
    REVIEWING,
    PUBLISHED,
    DEPRECATED
}
