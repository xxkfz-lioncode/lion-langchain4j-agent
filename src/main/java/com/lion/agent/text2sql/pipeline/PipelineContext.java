package com.lion.agent.text2sql.pipeline;

import java.util.HashMap;
import java.util.Map;

/**
 * 流程上下文(黑板模式)。
 * <p>
 * 步骤之间不直接互相调用, 而是把产出放进上下文, 后续步骤按 key 取用;
 * 这样步骤的增删/调序不需要改动其它步骤的签名。
 */
public class PipelineContext {

    private final Map<String, Object> attributes = new HashMap<>();
    private final String bizId;
    private boolean aborted;

    public PipelineContext(String bizId) {
        this.bizId = bizId;
    }

    public String bizId() {
        return bizId;
    }

    /** 存入步骤产出 */
    public void put(String key, Object value) {
        attributes.put(key, value);
    }

    /** 读取前序步骤产出(调用方保证类型, 框架不做强转校验) */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) attributes.get(key);
    }

    /**
     * 提前终止流程: 后续步骤全部标记 SKIPPED。
     * 用于正常业务分支的"提前收敛"(如澄清问题、无召回结果), 与异常失败不同。
     */
    public void abort() {
        this.aborted = true;
    }

    public boolean isAborted() {
        return aborted;
    }
}
