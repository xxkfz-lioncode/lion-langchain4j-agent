package com.lion.agent.ai.observability;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * LLM 调用链路详情(方案B: 通过 biz_id 打通三张表)。
 * <p>
 * 关联关系: 一次用户提问生成一个 bizId, 该次提问触发的每轮模型调用在
 * llm_call_log 落一条记录(工具循环时有多条), 产生的每条消息(用户提问/
 * 工具结果/AI 回复)在 chat_message 落一行且盖同一个 bizId 章 ——
 * 以 bizId 为纽带即可还原"一次提问的全链路视图"。
 */
@Data
public class LlmTraceVO {

    /** 业务追踪id */
    private String bizId;

    /** 同一次提问的全部模型调用(按 id 升序 = 执行顺序), 1 次提问至少 1 条 */
    private List<LlmCallLog> chain;

    /** 该次提问产生/消费的全部消息(按 id 升序): 用户提问, 工具消息, AI 回复 */
    private List<TraceMessage> messages;

    /** 关联的消息明细(取自 chat_message 展示字段) */
    @Data
    @AllArgsConstructor
    public static class TraceMessage {

        /** 消息行 id(排序用) */
        private Long id;

        /** 角色: user / assistant / tool(system 不盖章节外) */
        private String role;

        /** 消息文本; 纯工具调用等复杂消息可能为 null */
        private String content;

        /** 入库时间 */
        private LocalDateTime createTime;
    }
}
