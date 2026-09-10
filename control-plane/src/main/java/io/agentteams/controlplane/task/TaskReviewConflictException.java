package io.agentteams.controlplane.task;

/** 结果评审状态冲突（G02 D4）：仅 SUBMITTED 结果可评审。 */
public final class TaskReviewConflictException extends RuntimeException {

    private final String code;

    public TaskReviewConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
