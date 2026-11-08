package com.lion.agent.text2sql.manage.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lion.agent.common.PageResult;
import com.lion.agent.common.exception.BusinessException;
import com.lion.agent.text2sql.entity.DataFewshotEntity;
import com.lion.agent.text2sql.mapper.DataFewshotMapper;
import com.lion.agent.text2sql.manage.service.FewshotManageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * Few-shot 示例库管理实现。
 */
@Service
@RequiredArgsConstructor
public class FewshotManageServiceImpl implements FewshotManageService {

    private final DataFewshotMapper fewshotMapper;

    @Override
    public PageResult<DataFewshotEntity> page(int page, int size, String keyword) {
        Page<DataFewshotEntity> p = fewshotMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<DataFewshotEntity>()
                        .and(StringUtils.hasText(keyword), w -> w
                                .like(DataFewshotEntity::getQuestion, keyword)
                                .or().like(DataFewshotEntity::getExplanation, keyword)
                                .or().like(DataFewshotEntity::getTables, keyword))
                        .orderByDesc(DataFewshotEntity::getCreateTime));
        return PageResult.of(p.getTotal(), p.getRecords());
    }

    @Override
    public DataFewshotEntity create(DataFewshotEntity entity) {
        validate(entity);
        entity.setId(null);
        entity.setEnabled(entity.getEnabled() == null ? 1 : entity.getEnabled());
        entity.setUseCount(entity.getUseCount() == null ? 0 : entity.getUseCount());
        entity.setAvgRating(entity.getAvgRating() == null ? new java.math.BigDecimal("5.00") : entity.getAvgRating());
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        fewshotMapper.insert(entity);
        return entity;
    }

    @Override
    public DataFewshotEntity update(Long id, DataFewshotEntity entity) {
        DataFewshotEntity db = requireExists(id);
        validate(entity);
        db.setQuestion(entity.getQuestion());
        db.setSqlText(entity.getSqlText());
        db.setTables(entity.getTables());
        db.setExplanation(entity.getExplanation());
        db.setEnabled(entity.getEnabled() == null ? db.getEnabled() : entity.getEnabled());
        db.setUpdateTime(LocalDateTime.now());
        fewshotMapper.updateById(db);
        return db;
    }

    @Override
    public void delete(Long id) {
        requireExists(id);
        fewshotMapper.deleteById(id);
    }

    private DataFewshotEntity requireExists(Long id) {
        DataFewshotEntity db = fewshotMapper.selectById(id);
        if (db == null) {
            throw new BusinessException("示例不存在: " + id);
        }
        return db;
    }

    private void validate(DataFewshotEntity entity) {
        if (!StringUtils.hasText(entity.getQuestion())) {
            throw new BusinessException("标准问题不能为空");
        }
        if (!StringUtils.hasText(entity.getSqlText())) {
            throw new BusinessException("标准 SQL 不能为空");
        }
    }
}
