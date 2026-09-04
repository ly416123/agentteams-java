package io.agentteams.manager;

import io.agentteams.application.api.ModelPrice;
import io.agentteams.application.api.ModelPriceCatalog;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelCostCalculatorTest {
    @Test
    void matchesProviderModelAndCurrencyAndCalculatesInputAndOutputSeparately() {
        InMemoryModelPriceCatalog catalog = new InMemoryModelPriceCatalog(List.of(
                new ModelPrice("openai", "gpt-4o", "USD",
                        new BigDecimal("0.000005"), new BigDecimal("0.000015"))));

        ModelCostEstimate estimate = new ModelCostCalculator(catalog)
                .estimate("openai", "gpt-4o", "usd", new ModelTokenUsage(2_000, 1_000));

        assertThat(estimate.status()).isEqualTo(ModelCostEstimate.Status.ESTIMATED);
        assertThat(estimate.inputCost()).isEqualByComparingTo("0.010000");
        assertThat(estimate.outputCost()).isEqualByComparingTo("0.015000");
        assertThat(estimate.estimatedCost()).isEqualByComparingTo("0.025000");
        assertThat(estimate.currency()).isEqualTo("USD");
        assertThat(estimate.isPriced()).isTrue();
        assertThat(estimate.reason()).isNull();
    }

    @Test
    void returnsExplicitUnpricedResultWhenProviderModelOrCurrencyIsMissing() {
        InMemoryModelPriceCatalog catalog = new InMemoryModelPriceCatalog();
        catalog.register(new ModelPrice("openai", "gpt-4o", "USD",
                new BigDecimal("0.1"), new BigDecimal("0.2")));

        ModelCostEstimate result = new ModelCostCalculator(catalog)
                .estimate("anthropic", "claude", "USD", 10, 20);

        assertThat(result.status()).isEqualTo(ModelCostEstimate.Status.UNPRICED);
        assertThat(result.isPriced()).isFalse();
        assertThat(result.inputCost()).isNull();
        assertThat(result.outputCost()).isNull();
        assertThat(result.estimatedCost()).isNull();
        assertThat(result.reason()).contains("No price configured");
    }

    @Test
    void rejectsNegativeTokensAndInvalidPrices() {
        assertThatThrownBy(() -> new ModelTokenUsage(-1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");
        assertThatThrownBy(() -> new ModelPrice("openai", "gpt-4o", "USD",
                new BigDecimal("-0.1"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputPricePerToken");
        assertThatThrownBy(() -> new ModelPrice("openai", "gpt-4o", "not-a-currency",
                BigDecimal.ZERO, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO 4217");
    }

    @Test
    void allowsZeroPriceButDoesNotConfuseItWithMissingPrice() {
        InMemoryModelPriceCatalog catalog = new InMemoryModelPriceCatalog();
        catalog.register(new ModelPrice("local", "model", "CNY", BigDecimal.ZERO, BigDecimal.ZERO));

        ModelCostEstimate estimate = new ModelCostCalculator(catalog)
                .estimate("local", "model", "CNY", 100, 200);

        assertThat(estimate.status()).isEqualTo(ModelCostEstimate.Status.ESTIMATED);
        assertThat(estimate.estimatedCost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(estimate.isPriced()).isTrue();
    }
}
