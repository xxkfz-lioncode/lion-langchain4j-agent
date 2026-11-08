package com.lion.agent.text2sql.manage.service;

import com.lion.agent.common.PageResult;
import com.lion.agent.text2sql.entity.DataQueryFeedbackEntity;

/**
 * 反馈记录管理(运营处理页)。
 * <p>
 * 反馈由用户在问数结果页点踩/点赞产生, 运营侧唯一动作是推进处理状态:
 * PENDING → HANDLED(已处理, 可顺带沉淀为 few-shot) / IGNORED(已忽略)。
 * 处理逻辑复用 {@link com.lion.agent.text2sql.service.FeedbackService#markHandled}。
 */
public interface FeedbackManageService {

    /**
     * 分页查询反馈。
     *
     * @param status 处理状态精确过滤(PENDING/HANDLED/IGNORED), 可空
     */
    PageResult<DataQueryFeedbackEntity> page(int page, int size, String status);
}
