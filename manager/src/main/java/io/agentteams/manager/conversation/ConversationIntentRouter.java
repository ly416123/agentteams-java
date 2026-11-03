package io.agentteams.manager.conversation;

import io.agentteams.manager.ConversationIntent;

/** Classifies a user message before any task side effect can occur. */
@FunctionalInterface
public interface ConversationIntentRouter {
    ConversationIntent route(ConversationRuntimePort.Context context, ConversationOwner owner, String content);

    static ConversationIntentRouter failClosed() {
        return (context, owner, content) -> ConversationIntent.clarify("我暂时无法判断这条消息是否需要创建任务，请补充说明。");
    }
}
