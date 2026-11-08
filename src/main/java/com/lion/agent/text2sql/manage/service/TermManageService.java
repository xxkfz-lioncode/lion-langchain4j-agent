package com.lion.agent.text2sql.manage.service;

import com.lion.agent.common.PageResult;
import com.lion.agent.text2sql.entity.DataTermEntity;

/**
 * 业务术语管理(语义层配置页)。
 * <p>
 * 术语是 Text2SQL 召回与口径生成的核心配置: term/aliases 参与关键词召回,
 * calcRule/filterRule 会注入 LLM prompt。
 */
public interface TermManageService {

    /** 分页查询(keyword 模糊匹配术语/同义词/定义) */
    PageResult<DataTermEntity> page(int page, int size, String keyword);

    /** 新增术语 */
    DataTermEntity create(DataTermEntity entity, String operator);

    /** 更新术语 */
    DataTermEntity update(Long id, DataTermEntity entity);

    /** 删除术语 */
    void delete(Long id);
}
