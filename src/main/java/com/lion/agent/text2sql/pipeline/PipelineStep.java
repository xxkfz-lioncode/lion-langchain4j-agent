package com.lion.agent.text2sql.pipeline;

/**
 * 流程中的一个步骤定义。
 */
public final class PipelineStep {

    /**
     * 步骤执行体。
     *
     * @param ctx 流程上下文(读前序产出 / 写本步产出)
     * @param out 步骤内细粒度进度输出
     * @throws Exception 抛出任意异常即本步失败, 流程终止
     */
    @FunctionalInterface
    public interface StepBody {
        void execute(PipelineContext ctx, StepEmitter out) throws Exception;
    }

    private final String id;
    private final String title;
    private final StepBody body;

    public PipelineStep(String id, String title, StepBody body) {
        this.id = id;
        this.title = title;
        this.body = body;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    void execute(PipelineContext ctx, PipelineListener listener) throws Exception {
        body.execute(ctx, message -> listener.onStepDetail(id, message));
    }
}
