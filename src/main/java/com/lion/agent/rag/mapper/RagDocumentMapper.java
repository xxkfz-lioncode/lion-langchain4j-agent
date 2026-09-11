package com.lion.agent.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lion.agent.rag.entity.RagDocumentEntity;
import org.apache.ibatis.annotations.Select;

/**
 * 知识库文档 Mapper(继承 MyBatis-Plus BaseMapper, 复用内置 CRUD API)
 */
public interface RagDocumentMapper extends BaseMapper<RagDocumentEntity> {

    /** 当前知识库总片段数(仅统计成功入库的文档) */
    @Select("SELECT IFNULL(SUM(segment_count), 0) FROM rag_document WHERE status = 1")
    long sumSegmentCount();
}
