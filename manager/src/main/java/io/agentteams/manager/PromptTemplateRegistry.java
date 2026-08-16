package io.agentteams.manager;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Named prompt templates with explicit variables and a hard rendered-context budget. */
public final class PromptTemplateRegistry {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9_.-]*)}");
    private final Map<String, PromptTemplate> templates;
    private final int maxContextCharacters;

    public PromptTemplateRegistry(Map<String, PromptTemplate> templates, int maxContextCharacters) {
        if (maxContextCharacters <= 0) throw new IllegalArgumentException("maxContextCharacters must be positive");
        this.templates = Map.copyOf(Objects.requireNonNull(templates, "templates"));
        this.maxContextCharacters = maxContextCharacters;
        this.templates.forEach((name, template) -> {
            if (!name.equals(template.name())) throw new IllegalArgumentException("template key/name mismatch");
            validatePlaceholders(template);
        });
    }

    public String render(String name, Map<String, String> variables) {
        PromptTemplate template = templates.get(name);
        if (template == null) throw new IllegalArgumentException("unknown prompt template: " + name);
        Objects.requireNonNull(variables, "variables");
        if (!template.allowedVariables().equals(variables.keySet())) {
            throw new IllegalArgumentException("prompt context variables must exactly match the template allow-list");
        }
        Matcher matcher = PLACEHOLDER.matcher(template.body());
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            String variable = matcher.group(1);
            String value = variables.get(variable);
            if (value == null) throw new IllegalArgumentException("missing prompt variable: " + variable);
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        String result = rendered.toString();
        if (result.length() > template.maxCharacters() || result.length() > maxContextCharacters) {
            throw new IllegalArgumentException("rendered prompt exceeds configured context boundary");
        }
        return result;
    }

    private static void validatePlaceholders(PromptTemplate template) {
        Matcher matcher = PLACEHOLDER.matcher(template.body());
        while (matcher.find()) {
            if (!template.allowedVariables().contains(matcher.group(1))) {
                throw new IllegalArgumentException("template contains undeclared variable: " + matcher.group(1));
            }
        }
    }

    public record PromptTemplate(String name, String body, Set<String> allowedVariables, int maxCharacters) {
        public PromptTemplate {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
            if (body == null || body.isBlank()) throw new IllegalArgumentException("body must not be blank");
            if (maxCharacters <= 0) throw new IllegalArgumentException("maxCharacters must be positive");
            allowedVariables = Set.copyOf(Objects.requireNonNull(allowedVariables, "allowedVariables"));
        }
    }
}
