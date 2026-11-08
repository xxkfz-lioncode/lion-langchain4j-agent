package com.lion.agent.text2sql.manage.service;

import com.lion.agent.common.PageResult;
import com.lion.agent.text2sql.entity.DataFewshotEntity;

/**
 * Few-shot 示例库管理(标准问答对配置页)。
 * <p>
 * enabled=1 的示例会按相似度召回并注入 SQL 生成 prompt, 是提升生成准确率的主要运营手段。
 */
public interface FewshotManageService {

    /** 分页查询(keyword 模糊匹配问题/口径说明/涉及表) */
    PageResult<DataFewshotEntity> page(int page, int size, String keyword);

    /** 新增示例 */
    DataFewshotEntity create(DataFewshotEntity entity);

    /** 更新示例 */
    DataFewshotEntity update(Long id, DataFewshotEntity entity);

    /** 删除示例 */
    void delete(Long id);
}
