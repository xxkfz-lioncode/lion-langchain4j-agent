package com.lion.agent.text2sql.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量级顺序流程编排器。
 * <p>
 * 特性:
 * <ul>
 *   <li>步骤按加入顺序串行执行, 每步开始/结束/耗时都通过 {@link PipelineListener} 广播</li>
 *   <li>某步抛异常 → 流程立即终止, 异常包装为 {@link PipelineStepException}(保留步骤 id 与原始异常)</li>
 *   <li>{@link PipelineContext#abort()} → 后续步骤全部 SKIPPED(正常业务分支的提前收敛)</li>
 *   <li>步骤是纯数据定义, 增删/调序/复用只改编排处, 不改步骤实现</li>
 * </ul>
 */
public final class Pipeline {

    private final List<PipelineStep> steps;

    private Pipeline(List<PipelineStep> steps) {
        this.steps = List.copyOf(steps);
    }

    /** 只读步骤清单(不执行), 供元数据查询/前端预渲染 */
    public List<PipelineStep> steps() {
        return steps;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 执行流程。
     *
     * @throws PipelineStepException 某步骤执行失败时抛出
     */
    public void run(PipelineContext ctx, PipelineListener listener) {
        PipelineListener l = listener != null ? listener : PipelineListener.NOOP;
        l.onPlan(steps);
        for (PipelineStep step : steps) {
            if (ctx.isAborted()) {
                l.onStepFinish(step.id(), step.title(), StepStatus.SKIPPED, 0, "前置步骤已终止流程");
                continue;
            }
            l.onStepStart(step.id(), step.title());
            long start = System.currentTimeMillis();
            try {
                step.execute(ctx, l);
                l.onStepFinish(step.id(), step.title(), StepStatus.SUCCESS,
                        System.currentTimeMillis() - start, "");
            } catch (Exception e) {
                l.onStepFinish(step.id(), step.title(), StepStatus.FAILED,
                        System.currentTimeMillis() - start,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                throw new PipelineStepException(step, e);
            }
        }
    }

    /** 流程构建器 */
    public static class Builder {

        private final List<PipelineStep> steps = new ArrayList<>();

        public Builder addStep(String id, String title, PipelineStep.StepBody body) {
            steps.add(new PipelineStep(id, title, body));
            return this;
        }

        public Pipeline build() {
            if (steps.isEmpty()) {
                throw new IllegalArgumentException("流程至少需要一个步骤");
            }
            return new Pipeline(steps);
        }
    }
}
