package com.lion.agent.text2sql.manage.service;

import com.lion.agent.common.PageResult;
import com.lion.agent.text2sql.entity.DataQueryLogEntity;

/**
 * 问数日志查询(只读审计页)。
 * <p>
 * 日志是全链路留痕, 只允许查看, 不提供编辑/单条删除;
 * 容量治理交给定时清理任务, 不在页面上操作。
 */
public interface QueryLogQueryService {

    /**
     * 分页查询日志。
     *
     * @param keyword 模糊匹配问题/生成 SQL, 可空
     * @param status  结果状态精确过滤(SUCCESS/EMPTY/TIMEOUT/ERROR), 可空
     */
    PageResult<DataQueryLogEntity> page(int page, int size, String keyword, String status);
}
