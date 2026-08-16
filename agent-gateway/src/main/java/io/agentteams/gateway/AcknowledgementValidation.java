package io.agentteams.gateway;

/** Durable validation result for an Agent command acknowledgement. */
public record AcknowledgementValidation(
        boolean accepted,
        long highestDeliveredSequence,
        String rejectionReason) {

    public AcknowledgementValidation {
        if (highestDeliveredSequence < 0) {
            throw new IllegalArgumentException("highestDeliveredSequence must not be negative");
        }
        if (!accepted && (rejectionReason == null || rejectionReason.isBlank())) {
            throw new IllegalArgumentException("rejectionReason is required for rejected acknowledgement");
        }
    }

    public static AcknowledgementValidation accepted(long highestDeliveredSequence) {
        return new AcknowledgementValidation(true, highestDeliveredSequence, "");
    }

    public static AcknowledgementValidation rejected(long highestDeliveredSequence, String reason) {
        return new AcknowledgementValidation(false, highestDeliveredSequence, reason);
    }
}
