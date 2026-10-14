package io.agentteams.worker.chatgpt;

import io.agentscope.core.model.ModelRegistry;

/** Registers the OAuth-backed Responses API provider with AgentScope. */
public final class ChatGptPlanModelProvider {
    private static final String MODEL_ID = "chatgpt-plan:[A-Za-z0-9._-]+";
    private static boolean registered;

    private ChatGptPlanModelProvider() { }

    public static synchronized void register() {
        if (registered) return;
        ModelRegistry.registerFactory(MODEL_ID, (modelId, context) -> ChatGptPlanModel.fromEnvironment(modelId));
        registered = true;
    }
}
