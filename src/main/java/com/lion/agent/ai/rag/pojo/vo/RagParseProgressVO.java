package com.lion.agent.ai.rag.pojo.vo;

import lombok.Data;

/**
 * 解析触发结果 / 进度快照(解析已异步化, 触发接口立即返回;
 * 前端通过文档列表轮询 status/progress 获取最新进度)
 */
@Data
public class RagParseProgressVO {

    /** 文档 id */
    private Long id;

    /** 文件名 */
    private String fileName;

    /** 解析状态: 0 待解析 / 1 已解析 / 2 解析中 / 3 解析失败 */
    private Integer status;

    /** 解析进度(0-100) */
    private Integer progress;

    /** 最近一次解析失败的错误原因(成功时为空) */
    private String errorMsg;

    /** 累计解析触发次数 */
    private Integer retryCount;
}
