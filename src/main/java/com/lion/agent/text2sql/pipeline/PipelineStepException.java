package com.lion.agent.text2sql.pipeline;

/**
 * 步骤执行失败异常: 携带失败步骤 id, cause 为原始异常。
 */
public class PipelineStepException extends RuntimeException {

    private final String stepId;

    public PipelineStepException(PipelineStep step, Throwable cause) {
        super(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(), cause);
        this.stepId = step.id();
    }

    public String getStepId() {
        return stepId;
    }
}
