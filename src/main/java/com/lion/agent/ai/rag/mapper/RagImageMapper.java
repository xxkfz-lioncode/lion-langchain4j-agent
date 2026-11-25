package com.lion.agent.ai.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lion.agent.ai.rag.pojo.entity.RagImageEntity;
import org.apache.ibatis.annotations.Mapper;

/** RAG 文档图片表 Mapper(图片提取留底, 删除/重解析清理用) */
@Mapper
public interface RagImageMapper extends BaseMapper<RagImageEntity> {
}
