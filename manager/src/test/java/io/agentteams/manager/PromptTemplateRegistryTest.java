package io.agentteams.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PromptTemplateRegistryTest {
    @Test
    void rendersOnlyDeclaredContextVariablesWithinTheGlobalBudget() {
        PromptTemplateRegistry registry = new PromptTemplateRegistry(Map.of(
                "task", new PromptTemplateRegistry.PromptTemplate(
                        "task", "Title: ${title}\nDescription: ${description}", Set.of("title", "description"), 80)),
                100);

        assertThat(registry.render("task", Map.of("title", "Login", "description", "Implement login")))
                .isEqualTo("Title: Login\nDescription: Implement login");
    }

    @Test
    void rejectsUnknownOrMissingVariablesInsteadOfSilentlyAppendingContext() {
        PromptTemplateRegistry registry = new PromptTemplateRegistry(Map.of(
                "task", new PromptTemplateRegistry.PromptTemplate(
                        "task", "Title: ${title}", Set.of("title"), 80)), 100);

        assertThatThrownBy(() -> registry.render("task", Map.of("title", "Login", "history", "secret context")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.render("task", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTemplatesAndRenderedContextThatExceedTheirBoundaries() {
        assertThatThrownBy(() -> new PromptTemplateRegistry(Map.of(
                "task", new PromptTemplateRegistry.PromptTemplate(
                        "task", "${title}", Set.of("title"), 3)), 100)
                .render("task", Map.of("title", "long")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PromptTemplateRegistry(Map.of(
                "task", new PromptTemplateRegistry.PromptTemplate(
                        "task", "${title}", Set.of("title"), 100)), 3)
                .render("task", Map.of("title", "long")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUndeclaredPlaceholdersAtRegistration() {
        assertThatThrownBy(() -> new PromptTemplateRegistry(Map.of(
                "task", new PromptTemplateRegistry.PromptTemplate(
                        "task", "${title} ${hidden}", Set.of("title"), 100)), 100))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
