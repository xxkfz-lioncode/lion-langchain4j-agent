package com.lion.agent.text2sql.pipeline;

/**
 * 步骤内细粒度进度输出: 由流程编排器注入, 输出会转发给 PipelineListener。
 * <p>
 * 用于步骤内部的关键子动作提示(如"已召回表: xxx"、"第 2 次自愈重试"),
 * 与步骤级状态事件(onStepStart/onStepFinish)互补。
 */
@FunctionalInterface
public interface StepEmitter {

    /** 输出一条步骤内进度 */
    void detail(String message);
}
