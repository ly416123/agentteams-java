package io.agentteams.controlplane.service;

import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.ModelPriceRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;

/** Applies provider snapshots only to explicitly configured project scopes. */
public final class ModelPriceSyncService {
    private static final String ACTOR = "price-sync";
    private final ModelPriceSyncPort source;
    private final FoundationPersistenceService persistence;
    private final List<ModelPriceSyncProperties.Target> targets;
    private final int maxQuotes;

    public ModelPriceSyncService(ModelPriceSyncPort source, FoundationPersistenceService persistence,
            List<ModelPriceSyncProperties.Target> targets, int maxQuotes) {
        this.source = java.util.Objects.requireNonNull(source, "source");
        this.persistence = java.util.Objects.requireNonNull(persistence, "persistence");
        this.targets = List.copyOf(java.util.Objects.requireNonNull(targets, "targets"));
        if (maxQuotes < 1 || maxQuotes > 10_000) throw new IllegalArgumentException("maxQuotes must be between 1 and 10000");
        this.maxQuotes = maxQuotes;
    }

    public RunResult runOnce(Instant now) {
        if (targets.isEmpty() || targets.stream().anyMatch(target -> target == null || !target.valid())) {
            return new RunResult(0, 0, 0, 0);
        }
        ModelPriceSyncPort.Snapshot snapshot = source.fetch();
        if (snapshot.quotes().size() > maxQuotes) throw new ModelPriceSyncException("snapshot exceeds configured quote limit");
        int inserted = 0;
        int skipped = 0;
        for (ModelPriceSyncProperties.Target target : targets) {
            for (ModelPriceSyncPort.Quote quote : snapshot.quotes()) {
                if (persistence.findModelPriceByNaturalKey(target.tenant(), target.project(), quote.provider(),
                        quote.model(), quote.currency(), quote.effectiveFrom()).isPresent()) {
                    skipped++;
                    continue;
                }
                String canonical = canonical(snapshot, target, quote);
                ModelPriceRecord price = new ModelPriceRecord(UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8)),
                        target.tenant(), target.project(), quote.provider(), quote.model(), quote.currency(),
                        quote.inputPricePerMillionTokens(), quote.outputPricePerMillionTokens(), quote.effectiveFrom(),
                        quote.effectiveTo(), "ACTIVE", now, now, 0, ACTOR, ACTOR);
                try {
                    persistence.createModelPrice(price, "price-sync/" + sha256(canonical), sha256("request/" + canonical));
                    inserted++;
                } catch (DuplicateKeyException race) {
                    skipped++;
                }
            }
        }
        return new RunResult(targets.size(), snapshot.quotes().size(), inserted, skipped);
    }

    private static String canonical(ModelPriceSyncPort.Snapshot snapshot,
            ModelPriceSyncProperties.Target target, ModelPriceSyncPort.Quote quote) {
        return String.join("\n", snapshot.sourceVersion(), target.tenant(), target.project(), quote.provider(),
                quote.model(), quote.currency(), quote.inputPricePerMillionTokens().toPlainString(),
                quote.outputPricePerMillionTokens().toPlainString(), quote.effectiveFrom().toString(),
                quote.effectiveTo() == null ? "" : quote.effectiveTo().toString());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    public record RunResult(int targetCount, int fetchedQuotes, int inserted, int skipped) { }
}
