package com.lion.agent.model.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lion.agent.model.entity.ModelConfigEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 模型配置 Mapper(包已加入 MybatisPlusConfig 的 @MapperScan)。
 */
@Mapper
public interface ModelConfigMapper extends BaseMapper<ModelConfigEntity> {
}
