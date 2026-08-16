package io.agentteams.runtime;

/**
 * A staged configuration that can be atomically made visible or discarded.
 * Implementations must make {@link #activate()} atomic from the runtime's point
 * of view: an activation failure must leave the previously active configuration
 * visible.
 */
public interface RuntimeConfigPrepared {
    void activate();

    void discard();
}
