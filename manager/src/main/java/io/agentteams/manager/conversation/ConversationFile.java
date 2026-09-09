package io.agentteams.manager.conversation;

import java.time.Instant;
import java.util.UUID;

/** A file produced by the conversation agent and persisted to object storage. */
public record ConversationFile(UUID id, UUID sessionId, String name, String contentType,
        long sizeBytes, String storageKey, Instant createdAt) { }
