package com.lion.agent.text2sql.service;

import com.lion.agent.common.PageResult;
import com.lion.agent.text2sql.pojo.dto.FeedbackRequest;
import com.lion.agent.text2sql.pojo.vo.BadCaseVO;

/**
 * 问数反馈与 bad case 闭环。
 */
public interface FeedbackService {

    /** 提交反馈 */
    void submit(FeedbackRequest request, Long userId);

    /** 待处理的 bad case 列表 */
    PageResult<BadCaseVO> listBadCases(int page, int size);

    /** 标记已处理(状态置为 HANDLED) */
    void markHandled(Long feedbackId, boolean promoteToFewshot);

    /**
     * 推进处理状态。
     *
     * @param status           目标状态: HANDLED(已处理) / IGNORED(已忽略)
     * @param promoteToFewshot 仅 HANDLED 且提供了纠正 SQL 时有效: 沉淀为 few-shot 示例
     */
    void updateStatus(Long feedbackId, String status, boolean promoteToFewshot);
}
