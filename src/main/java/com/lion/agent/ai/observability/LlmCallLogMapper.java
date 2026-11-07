package com.lion.agent.ai.observability;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * LLM 调用审计日志 Mapper(包已加入 MybatisPlusConfig 的 @MapperScan)。
 */
@Mapper
public interface LlmCallLogMapper extends BaseMapper<LlmCallLog> {
}
