package io.agentteams.manager.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.manager.ConversationIntent;
import io.agentteams.manager.CreateTaskIntent;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationIntentPolicyTest {
    private final ConversationIntentPolicy policy = new ConversationIntentPolicy();

    @Test
    void answersLiveStockLookupAsUnsupportedChatWithoutCreatingATask() {
        ConversationIntent routed = policy.apply("查看茅台的股票价格",
                ConversationIntent.createTask(task(List.of("market-data"))), false);

        assertThat(routed.type()).isEqualTo(ConversationIntent.Type.CHAT);
        assertThat(routed.response()).contains("没有接入实时搜索或行情工具");
    }

    @Test
    void asksBeforeCreatingTaskWhenUserDidNotClearlyRequestExecution() {
        ConversationIntent routed = policy.apply("茅台现在的股价是多少？",
                ConversationIntent.createTask(task(List.of("market-data"))), false);

        assertThat(routed.type()).isEqualTo(ConversationIntent.Type.CHAT);
        assertThat(routed.response()).contains("不会把这类查询转成后台任务");
    }

    @Test
    void doesNotCreateTaskWithoutRequiredWorkerCapabilities() {
        ConversationIntent routed = policy.apply("请帮我生成一份项目风险报告",
                ConversationIntent.createTask(task(List.of())), false);

        assertThat(routed.type()).isEqualTo(ConversationIntent.Type.CLARIFY);
        assertThat(routed.response()).contains("具备什么能力");
    }

    @Test
    void allowsExplicitSupportedBackgroundWork() {
        ConversationIntent routed = policy.apply("请帮我生成一份项目风险报告",
                ConversationIntent.createTask(task(List.of("report_generation"))), false);

        assertThat(routed.type()).isEqualTo(ConversationIntent.Type.CREATE_TASK);
    }

    private static CreateTaskIntent task(List<String> capabilities) {
        return new CreateTaskIntent("CREATE_TASK", "项目风险报告", "整理项目风险并生成报告",
                capabilities, 0, false);
    }
}
