package com.lion.agent.ai.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lion.agent.ai.rag.pojo.entity.RagSegmentEntity;
import org.apache.ibatis.annotations.Mapper;

/** RAG 片段表 Mapper(混合检索 BM25 关键词路数据源) */
@Mapper
public interface RagSegmentMapper extends BaseMapper<RagSegmentEntity> {
}