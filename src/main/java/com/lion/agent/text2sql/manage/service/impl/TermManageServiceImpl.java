package com.lion.agent.text2sql.manage.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lion.agent.common.PageResult;
import com.lion.agent.common.exception.BusinessException;
import com.lion.agent.text2sql.entity.DataTermEntity;
import com.lion.agent.text2sql.mapper.DataTermMapper;
import com.lion.agent.text2sql.manage.service.TermManageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 业务术语管理实现。
 */
@Service
@RequiredArgsConstructor
public class TermManageServiceImpl implements TermManageService {

    private final DataTermMapper termMapper;

    @Override
    public PageResult<DataTermEntity> page(int page, int size, String keyword) {
        Page<DataTermEntity> p = termMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<DataTermEntity>()
                        .and(StringUtils.hasText(keyword), w -> w
                                .like(DataTermEntity::getTerm, keyword)
                                .or().like(DataTermEntity::getAliases, keyword)
                                .or().like(DataTermEntity::getDefinition, keyword))
                        .orderByDesc(DataTermEntity::getUpdateTime));
        return PageResult.of(p.getTotal(), p.getRecords());
    }

    @Override
    public DataTermEntity create(DataTermEntity entity, String operator) {
        validate(entity);
        if (termMapper.selectCount(new LambdaQueryWrapper<DataTermEntity>()
                .eq(DataTermEntity::getTerm, entity.getTerm().trim())) > 0) {
            throw new BusinessException("术语已存在: " + entity.getTerm());
        }
        entity.setId(null);
        entity.setTerm(entity.getTerm().trim());
        entity.setEnabled(entity.getEnabled() == null ? 1 : entity.getEnabled());
        entity.setCreateBy(operator);
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        termMapper.insert(entity);
        return entity;
    }

    @Override
    public DataTermEntity update(Long id, DataTermEntity entity) {
        DataTermEntity db = requireExists(id);
        validate(entity);
        db.setTerm(entity.getTerm().trim());
        db.setAliases(entity.getAliases());
        db.setDefinition(entity.getDefinition());
        db.setTableName(entity.getTableName());
        db.setColumnName(entity.getColumnName());
        db.setCalcRule(entity.getCalcRule());
        db.setFilterRule(entity.getFilterRule());
        db.setEnabled(entity.getEnabled() == null ? db.getEnabled() : entity.getEnabled());
        db.setUpdateTime(LocalDateTime.now());
        termMapper.updateById(db);
        return db;
    }

    @Override
    public void delete(Long id) {
        requireExists(id);
        termMapper.deleteById(id);
    }

    private DataTermEntity requireExists(Long id) {
        DataTermEntity db = termMapper.selectById(id);
        if (db == null) {
            throw new BusinessException("术语不存在: " + id);
        }
        return db;
    }

    private void validate(DataTermEntity entity) {
        if (!StringUtils.hasText(entity.getTerm())) {
            throw new BusinessException("术语不能为空");
        }
        if (entity.getTerm().length() > 64) {
            throw new BusinessException("术语长度不能超过 64");
        }
    }
}
