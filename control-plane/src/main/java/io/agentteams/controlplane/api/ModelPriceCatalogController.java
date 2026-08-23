package io.agentteams.controlplane.api;

import io.agentteams.controlplane.persistence.ModelPriceRecord;
import io.agentteams.controlplane.service.ModelPriceCatalogService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/model-prices")
public final class ModelPriceCatalogController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private final ModelPriceCatalogService service;

    public ModelPriceCatalogController(ModelPriceCatalogService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ModelPriceResponse> createPrice(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody CreatePriceRequest request) {
        requireRequest(request);
        requireIdempotencyKey(idempotencyKey);
        ModelPriceRecord price = service.createPrice(idempotencyKey, request.toServiceInput());
        return ResponseEntity.status(201).body(ModelPriceResponse.from(price));
    }

    @GetMapping
    public List<ModelPriceResponse> listPrices() {
        return service.listPrices().stream().map(ModelPriceResponse::from).toList();
    }

    @GetMapping("/effective")
    public ResponseEntity<ModelPriceResponse> findEffective(
            @RequestParam String provider,
            @RequestParam String model,
            @RequestParam String currency,
            @RequestParam(required = false) Instant at) {
        return (at == null ? service.findEffectivePrice(provider, model, currency)
                : service.findEffectivePrice(provider, model, currency, at))
                .map(price -> ResponseEntity.ok(ModelPriceResponse.from(price)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{priceId}")
    public ModelPriceResponse getPrice(@PathVariable UUID priceId) {
        return ModelPriceResponse.from(service.getPrice(priceId));
    }

    @PatchMapping("/{priceId}")
    public ModelPriceResponse setLifecycle(@PathVariable UUID priceId,
            @RequestBody LifecycleRequest request) {
        requireRequest(request);
        if (request.lifecycleStatus() == null || request.lifecycleStatus().isBlank()) {
            throw new IllegalArgumentException("lifecycleStatus is required");
        }
        return ModelPriceResponse.from(service.setLifecycle(priceId, request.lifecycleStatus()));
    }

    public record CreatePriceRequest(String provider, String model, String currency,
            BigDecimal inputPricePerMillionTokens, BigDecimal outputPricePerMillionTokens,
            Instant effectiveFrom, Instant effectiveTo, String lifecycleStatus) {
        ModelPriceCatalogService.PriceInput toServiceInput() {
            return new ModelPriceCatalogService.PriceInput(provider, model, currency,
                    inputPricePerMillionTokens, outputPricePerMillionTokens,
                    effectiveFrom, effectiveTo, lifecycleStatus);
        }
    }

    public record LifecycleRequest(String lifecycleStatus) { }

    public record ModelPriceResponse(UUID id, String provider, String model, String currency,
            BigDecimal inputPricePerMillionTokens, BigDecimal outputPricePerMillionTokens,
            Instant effectiveFrom, Instant effectiveTo, String lifecycleStatus,
            Instant createdAt, Instant updatedAt, long version, String createdBy, String updatedBy) {
        static ModelPriceResponse from(ModelPriceRecord price) {
            return new ModelPriceResponse(price.id(), price.provider(), price.model(), price.currency(),
                    price.inputPricePerMillionTokens(), price.outputPricePerMillionTokens(),
                    price.effectiveFrom(), price.effectiveTo(), price.lifecycleStatus(), price.createdAt(),
                    price.updatedAt(), price.version(), price.createdBy(), price.updatedBy());
        }
    }

    private static void requireRequest(Object request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
    }

    private static void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        if (key.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key must be at most 255 characters");
        }
    }
}
