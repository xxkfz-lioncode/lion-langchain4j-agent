package com.lion.agent.ai.rag.pojo.vo;

import lombok.Data;

import java.util.List;

/**
 * 知识问答应答 VO: AI 回答文本 + 引用来源列表
 */
@Data
public class RagAnswerVO {

    /** AI 回答文本(markdown) */
    private String answer;

    /** 引用来源(检索到的相关片段, 按匹配度倒序; 由检索层注入而非 LLM 自述, 保证真实可查) */
    private List<RagSourceVO> sources;
}
