package io.agentteams.manager.conversation;

import io.agentteams.manager.ConversationIntent;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Applies deterministic safety rules after model routing and before task side effects. */
public final class ConversationIntentPolicy {
    private static final Pattern EXPLICIT_EXECUTION = Pattern.compile(
            "(?:创建|新建|提交|发起)任务|安排.{0,12}(?:团队|代理|工作节点).{0,16}(?:执行|处理|完成)|"
                    + "让.{0,12}(?:团队|代理|工作节点).{0,20}(?:执行|处理|完成)|委派.{0,20}(?:执行|处理|完成)|"
                    + "(?:实现|开发|编写|生成|整理|修改|修复|构建|部署|打包|运行工作流)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LIVE_LOOKUP = Pattern.compile(
            "(?:股票|股价|证券行情|股票价格|实时行情|汇率|天气|新闻|比分).{0,24}"
                    + "(?:价格|行情|多少|最新|现在|当前|今天|实时|查询|查看|查一下)|"
                    + "(?:最新|现在|当前|今天|实时).{0,12}(?:价格|行情|股价|天气|新闻|汇率|比分)|"
                    + "(?:搜索|查|查看|查询).{0,10}(?:新闻|股票|行情|天气|汇率|比分)|"
                    + "(?:联网搜索|上网搜索|搜索网页|网上查询)",
            Pattern.CASE_INSENSITIVE);

    public boolean requiresUnavailableLiveLookupResponse(String content, boolean webSearchAvailable) {
        Objects.requireNonNull(content, "content");
        return !webSearchAvailable && !isExplicitExecutionRequest(content)
                && LIVE_LOOKUP.matcher(content.toLowerCase(Locale.ROOT)).find();
    }

    public ConversationIntent apply(String content, ConversationIntent proposed, boolean webSearchAvailable) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(proposed, "proposed");
        if (requiresUnavailableLiveLookupResponse(content, webSearchAvailable)) {
            return ConversationIntent.chat(unavailableLiveLookupResponse());
        }
        if (proposed.type() != ConversationIntent.Type.CREATE_TASK) return proposed;
        if (!isExplicitExecutionRequest(content)) {
            return ConversationIntent.clarify(
                    "我暂时不会把普通问题自动转成后台任务。你希望我直接回答，还是明确安排团队执行一项工作？");
        }
        if (proposed.task().requiredCapabilities().isEmpty()) {
            return ConversationIntent.clarify(
                    "我还无法确认这项工作需要团队具备什么能力，因此先不创建任务。请补充所需技能、工具或预期产出。");
        }
        return proposed;
    }

    public static boolean isExplicitExecutionRequest(String content) {
        return EXPLICIT_EXECUTION.matcher(content).find();
    }

    public static String unavailableLiveLookupResponse() {
        return "当前对话没有接入实时搜索或行情工具，因此无法核验最新数据，也不会把这类查询转成后台任务。你可以稍后重试，或明确安排具备相应搜索能力的团队执行。";
    }
}
