package com.lion.agent.text2sql.manage.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lion.agent.common.PageResult;
import com.lion.agent.common.exception.BusinessException;
import com.lion.agent.text2sql.entity.DataMaskRuleEntity;
import com.lion.agent.text2sql.mapper.DataMaskRuleMapper;
import com.lion.agent.text2sql.manage.service.MaskRuleManageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 脱敏规则管理实现。
 */
@Service
@RequiredArgsConstructor
public class MaskRuleManageServiceImpl implements MaskRuleManageService {

    /** 与 SqlGuardImpl.buildMaskFunction 支持的类型保持一致 */
    private static final Set<String> MASK_TYPES = Set.of("PHONE", "ID_CARD", "EMAIL", "NAME", "BANK_CARD", "CUSTOM");

    private final DataMaskRuleMapper maskRuleMapper;

    @Override
    public PageResult<DataMaskRuleEntity> page(int page, int size, String keyword) {
        Page<DataMaskRuleEntity> p = maskRuleMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<DataMaskRuleEntity>()
                        .and(StringUtils.hasText(keyword), w -> w
                                .like(DataMaskRuleEntity::getTableName, keyword)
                                .or().like(DataMaskRuleEntity::getColumnName, keyword))
                        .orderByDesc(DataMaskRuleEntity::getUpdateTime));
        return PageResult.of(p.getTotal(), p.getRecords());
    }

    @Override
    public DataMaskRuleEntity create(DataMaskRuleEntity entity) {
        validate(entity);
        entity.setId(null);
        entity.setEnabled(entity.getEnabled() == null ? 1 : entity.getEnabled());
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        maskRuleMapper.insert(entity);
        return entity;
    }

    @Override
    public DataMaskRuleEntity update(Long id, DataMaskRuleEntity entity) {
        DataMaskRuleEntity db = requireExists(id);
        validate(entity);
        db.setTableName(entity.getTableName());
        db.setColumnName(entity.getColumnName());
        db.setMaskType(entity.getMaskType());
        db.setExpression(entity.getExpression());
        db.setEnabled(entity.getEnabled() == null ? db.getEnabled() : entity.getEnabled());
        db.setUpdateTime(LocalDateTime.now());
        maskRuleMapper.updateById(db);
        return db;
    }

    @Override
    public void delete(Long id) {
        requireExists(id);
        maskRuleMapper.deleteById(id);
    }

    private DataMaskRuleEntity requireExists(Long id) {
        DataMaskRuleEntity db = maskRuleMapper.selectById(id);
        if (db == null) {
            throw new BusinessException("脱敏规则不存在: " + id);
        }
        return db;
    }

    private void validate(DataMaskRuleEntity entity) {
        if (!StringUtils.hasText(entity.getTableName())) {
            throw new BusinessException("表名不能为空(* 表示所有表)");
        }
        if (!StringUtils.hasText(entity.getColumnName())) {
            throw new BusinessException("字段名不能为空(* 表示所有字段)");
        }
        String type = entity.getMaskType();
        if (!StringUtils.hasText(type) || !MASK_TYPES.contains(type)) {
            throw new BusinessException("脱敏类型必须是 " + MASK_TYPES);
        }
        if ("CUSTOM".equals(type) && !StringUtils.hasText(entity.getExpression())) {
            throw new BusinessException("CUSTOM 类型必须填写自定义脱敏 SQL 表达式");
        }
    }
}
