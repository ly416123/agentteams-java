package io.agentteams.controlplane.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeamPlanParserTest {
    private static final UUID PARENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Test
    void parsesTwoIndependentExecutorItemsWithStableIds() {
        String output = """
                {"plan":[
                  {"sequence":1,"title":"检查 API","requiredCapabilities":["http"],"dependsOn":[]},
                  {"sequence":2,"title":"检查数据库","requiredCapabilities":["sql"],"dependsOn":[]}
                ],"summary":"已拆解"}
                """;

        List<SubtaskService.SubtaskSpec> first = TeamPlanParser.parse(PARENT_ID, output);
        List<SubtaskService.SubtaskSpec> second = TeamPlanParser.parse(PARENT_ID, output);

        assertThat(first).hasSize(2);
        assertThat(first.get(0).subtaskId()).isEqualTo(second.get(0).subtaskId());
        assertThat(first.get(0).title()).isEqualTo("检查 API");
        assertThat(first.get(0).requiredCapabilities()).containsExactly("http");
        assertThat(first.get(1).dependencyIds()).isEmpty();
    }

    @Test
    void parsesTaskScopedMcpRequirementsForEachSubtask() {
        String output = """
                {"plan":[
                  {"sequence":1,"title":"检索简报","requiredMcpTools":["search_news","web_search"]},
                  {"sequence":2,"title":"生成 PDF","requiredMcpTools":["create_task_pdf_artifact"],"dependsOn":[1]}
                ]}
                """;

        List<SubtaskService.SubtaskSpec> parsed = TeamPlanParser.parse(PARENT_ID, output);

        assertThat(parsed.get(0).requiredMcpTools()).containsExactly("search_news", "web_search");
        assertThat(parsed.get(1).requiredMcpTools()).containsExactly("create_task_pdf_artifact");
        assertThat(parsed.get(1).dependencyIds()).containsExactly(parsed.get(0).subtaskId());
    }

    @Test
    void rejectsPlanThatDoesNotAssignTwoWorkers() {
        assertThatThrownBy(() -> TeamPlanParser.parse(PARENT_ID,
                "{\"plan\":[{\"sequence\":1,\"title\":\"只有一个\"}]}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly two");
    }

    @Test
    void parsesJsonCodeBlockAfterLeaderExplanation() {
        String output = """
                计划如下，两个子任务相互独立：

                ```json
                {"plan":[
                  {"sequence":1,"title":"健康检查","dependsOn":[]},
                  {"sequence":2,"title":"背景整理","dependsOn":[]}
                ]}
                ```
                """;

        assertThat(TeamPlanParser.parse(PARENT_ID, output))
                .extracting(SubtaskService.SubtaskSpec::title)
                .containsExactly("健康检查", "背景整理");
    }
}
