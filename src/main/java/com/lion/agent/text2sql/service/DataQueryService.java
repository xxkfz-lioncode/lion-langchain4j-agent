package com.lion.agent.text2sql.service;

import com.lion.agent.text2sql.pipeline.StepMeta;
import com.lion.agent.text2sql.pojo.dto.Text2SqlQuery;
import com.lion.agent.text2sql.pojo.vo.Text2SqlResult;

import java.util.List;

/**
 * 问数编排门面: 由流程编排器(pipeline 包)驱动, 串起 召回 → 生成 → 校验 → 执行 → 可视化 → 留痕。
 * <p>
 * 时序见设计文档 §5.9.1。
 */
public interface DataQueryService {

    /**
     * 同步问数(供 Controller 兜底 / 导出调用)。
     */
    Text2SqlResult query(Text2SqlQuery query, Long userId);

    /**
     * 流程步骤清单(编排处定义的元数据), 供前端在提问前预渲染步骤条。
     */
    List<StepMeta> describePipeline();

    /**
     * 流式问数: 通过回调逐阶段推送(plan / step / progress / sql / result / error)。
     */
    void queryStream(Text2SqlQuery query, Long userId, Text2SqlEventSink sink);

    /** 流式事件回调 */
    interface Text2SqlEventSink {

        /** 阶段进度提示 */
        void onProgress(String message);

        /** 已生成 SQL */
        void onSql(String sql, String explanation);

        /** 最终结果 */
        void onResult(Text2SqlResult result);

        /** 出错(errorType + message) */
        void onError(String errorType, String message);

        /**
         * 流程计划: 执行开始时推送全部步骤清单, 前端先渲染完整步骤条再逐个点亮。
         */
        default void onPlan(List<StepMeta> steps) {
        }

        /**
         * 步骤状态变更。
         *
         * @param status RUNNING / SUCCESS / FAILED / SKIPPED
         * @param costMs 步骤耗时(SKIPPED 为 0)
         * @param message FAILED 时的失败原因, 其余为空
         */
        default void onStep(String id, String title, String status, long costMs, String message) {
        }
    }
}
