package com.lion.agent.text2sql.pipeline;

import java.util.List;

/**
 * 流程执行监听器: 感知计划、每个步骤的开始/细节/结束。
 * <p>
 * 流式场景由此桥接到 SSE 推送; 同步场景传空实现即可。
 */
public interface PipelineListener {

    PipelineListener NOOP = new PipelineListener() {
    };

    /**
     * 流程开始: 推送全部步骤清单(前端可先渲染完整步骤条, 再随执行逐个点亮)。
     */
    default void onPlan(List<PipelineStep> steps) {
    }

    /** 某步骤开始执行 */
    default void onStepStart(String stepId, String title) {
    }

    /** 步骤内细粒度进度 */
    default void onStepDetail(String stepId, String message) {
    }

    /**
     * 某步骤执行结束。
     *
     * @param status SUCCESS / FAILED / SKIPPED
     * @param costMs 步骤耗时(SKIPPED 为 0)
     * @param message FAILED 时的失败原因
     */
    default void onStepFinish(String stepId, String title, StepStatus status, long costMs, String message) {
    }
}
