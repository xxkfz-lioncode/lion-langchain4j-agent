package com.lion.agent.text2sql.manage.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lion.agent.common.PageResult;
import com.lion.agent.text2sql.entity.DataQueryFeedbackEntity;
import com.lion.agent.text2sql.mapper.DataQueryFeedbackMapper;
import com.lion.agent.text2sql.manage.service.FeedbackManageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 反馈记录查询实现(只读; 处理动作走 FeedbackService.markHandled)。
 */
@Service
@RequiredArgsConstructor
public class FeedbackManageServiceImpl implements FeedbackManageService {

    private final DataQueryFeedbackMapper feedbackMapper;

    @Override
    public PageResult<DataQueryFeedbackEntity> page(int page, int size, String status) {
        Page<DataQueryFeedbackEntity> p = feedbackMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<DataQueryFeedbackEntity>()
                        .eq(StringUtils.hasText(status), DataQueryFeedbackEntity::getHandleStatus, status)
                        .orderByDesc(DataQueryFeedbackEntity::getCreateTime));
        return PageResult.of(p.getTotal(), p.getRecords());
    }
}
