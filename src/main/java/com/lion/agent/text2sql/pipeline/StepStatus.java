package com.lion.agent.text2sql.pipeline;

/**
 * 步骤执行状态。
 */
public enum StepStatus {

    /** 执行中 */
    RUNNING,

    /** 执行成功 */
    SUCCESS,

    /** 执行失败(流程终止) */
    FAILED,

    /** 已跳过(前序步骤终止了流程, 如澄清/无召回结果场景) */
    SKIPPED
}
