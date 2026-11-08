package com.lion.agent.text2sql.manage.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lion.agent.common.PageResult;
import com.lion.agent.text2sql.entity.DataQueryLogEntity;
import com.lion.agent.text2sql.mapper.DataQueryLogMapper;
import com.lion.agent.text2sql.manage.service.QueryLogQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 问数日志只读查询实现。
 */
@Service
@RequiredArgsConstructor
public class QueryLogQueryServiceImpl implements QueryLogQueryService {

    private final DataQueryLogMapper queryLogMapper;

    @Override
    public PageResult<DataQueryLogEntity> page(int page, int size, String keyword, String status) {
        Page<DataQueryLogEntity> p = queryLogMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<DataQueryLogEntity>()
                        .and(StringUtils.hasText(keyword), w -> w
                                .like(DataQueryLogEntity::getQuestion, keyword)
                                .or().like(DataQueryLogEntity::getGeneratedSql, keyword))
                        .eq(StringUtils.hasText(status), DataQueryLogEntity::getResultStatus, status)
                        .orderByDesc(DataQueryLogEntity::getCreateTime));
        return PageResult.of(p.getTotal(), p.getRecords());
    }
}
