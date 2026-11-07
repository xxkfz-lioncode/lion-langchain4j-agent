package com.lion.agent.ai.observability;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * LLM 调用审计日志(可观测性落库, 与 chat_message 记忆表职责分离)。
 * <p>
 * 职责划分:
 * <ul>
 *   <li>{@code chat_message} —— 对话记忆, message_json 会被重新加载还原进 prompt;</li>
 *   <li>本表 —— 调用审计(模型/Token/耗时/成败), 用于观测与排障, 不存消息内容,
 *       需要内容时按 biz_id / memory_id 去 chat_message 关联。</li>
 * </ul>
 */
@Data
@TableName("llm_call_log")
public class LlmCallLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务追踪id(一次用户提问一个, 串起该次提问的多轮模型调用) */
    private String bizId;

    /** 会话记忆id(形如 chat:1:2); 非对话链路的调用(结构化输出等)为空 */
    private String memoryId;

    /** 用户id(冗余自 memoryId, 便于按用户查询) */
    private Long userId;

    /** 模型名 */
    private String model;

    /** 输入 token 数 */
    private Integer inputTokens;

    /** 输出 token 数 */
    private Integer outputTokens;

    /** 总 token 数 */
    private Integer totalTokens;

    /** 结束原因: STOP / TOOL_EXECUTION / LENGTH / 异常等 */
    private String finishReason;

    /** 本次模型调用耗时(毫秒) */
    private Long durationMs;

    /** 是否成功(1=成功, 0=失败) */
    private Integer success;

    /** 失败原因(仅失败记录) */
    private String errorMessage;

    /** 落库时间 */
    private LocalDateTime createTime;
}
