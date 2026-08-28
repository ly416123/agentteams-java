package io.agentteams.controlplane.usage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Evaluates active budget policies and delivers durable threshold events with retry. */
public final class UsageBudgetDeliveryService {
    private static final int RETRY_ERROR_LIMIT = 500;
    private static final int DEFAULT_RETRY_LIMIT = 100;

    private final UsageBudgetRepository events;
    private final UsageBudgetService budgets;
    private final UsageBudgetNotificationPort notifications;
    private final Clock clock;
    private final Duration retryDelay;

    public UsageBudgetDeliveryService(UsageBudgetRepository events, UsageBudgetNotificationPort notifications,
            Clock clock, Duration retryDelay) {
        this(events, null, notifications, clock, retryDelay);
    }

    public UsageBudgetDeliveryService(UsageBudgetRepository events, UsageBudgetService budgets,
            UsageBudgetNotificationPort notifications, Clock clock, Duration retryDelay) {
        this.events = Objects.requireNonNull(events, "events");
        this.budgets = budgets;
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retryDelay = requirePositive(retryDelay, "retryDelay");
    }

    public RunResult runOnce(Instant now, int maxPolicies) {
        Objects.requireNonNull(now, "now");
        if (budgets == null) throw new IllegalStateException("budget evaluation is not configured");
        if (maxPolicies < 1 || maxPolicies > 1000) {
            throw new IllegalArgumentException("maxPolicies must be between 1 and 1000");
        }
        DeliveryResult before = deliverDue(now);
        List<UsageBudgetPolicy> policies = events.findActive(maxPolicies);
        for (UsageBudgetPolicy policy : policies) budgets.evaluateCurrent(policy);
        DeliveryResult after = deliverDue(now);
        return new RunResult(policies.size(), before.delivered() + after.delivered(), before.failed() + after.failed());
    }

    public DeliveryResult deliverDue(Instant now) {
        Objects.requireNonNull(now, "now");
        List<UsageBudgetEvent> candidates = new ArrayList<>(events.findPending(DEFAULT_RETRY_LIMIT));
        candidates.addAll(events.findDue(now, DEFAULT_RETRY_LIMIT));
        int delivered = 0;
        int failed = 0;
        for (UsageBudgetEvent candidate : candidates) {
            UsageBudgetEvent event = candidate.status() == UsageBudgetEvent.Status.FAILED
                    ? events.claim(candidate, now).orElse(null) : candidate;
            if (event == null) continue;
            try {
                UsageBudgetNotificationPort.NotificationResult result = notifications.notify(event.notification());
                if (!result.delivered()) throw new IllegalStateException("notification channel did not deliver the budget alert");
                events.markSent(event.id(), now);
                delivered++;
            } catch (RuntimeException error) {
                events.markFailed(event.id(), now.plus(backoff(event.attempts())), sanitize(error), now);
                failed++;
            }
        }
        return new DeliveryResult(delivered, failed);
    }

    private Duration backoff(int attempts) {
        int exponent = Math.min(Math.max(attempts - 1, 0), 6);
        return retryDelay.multipliedBy(1L << exponent);
    }

    private static String sanitize(RuntimeException error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return message.length() <= RETRY_ERROR_LIMIT ? message : message.substring(0, RETRY_ERROR_LIMIT);
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    public record DeliveryResult(int delivered, int failed) { }

    public record RunResult(int evaluatedPolicies, int delivered, int failed) {
        public UsageBudgetScheduler.RunResult toSchedulerResult() {
            return new UsageBudgetScheduler.RunResult(true, evaluatedPolicies, delivered, failed);
        }
    }
}
