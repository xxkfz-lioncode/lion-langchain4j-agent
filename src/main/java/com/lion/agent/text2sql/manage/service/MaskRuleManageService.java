package com.lion.agent.text2sql.manage.service;

import com.lion.agent.common.PageResult;
import com.lion.agent.text2sql.entity.DataMaskRuleEntity;

/**
 * 脱敏规则管理(运营配置页)。
 * <p>
 * 规则改动即时生效: SqlGuard 每次校验都会现查启用中的规则, 无需刷新缓存。
 */
public interface MaskRuleManageService {

    /** 分页查询(keyword 模糊匹配表名/字段名) */
    PageResult<DataMaskRuleEntity> page(int page, int size, String keyword);

    /** 新增规则 */
    DataMaskRuleEntity create(DataMaskRuleEntity entity);

    /** 更新规则 */
    DataMaskRuleEntity update(Long id, DataMaskRuleEntity entity);

    /** 删除规则 */
    void delete(Long id);
}
