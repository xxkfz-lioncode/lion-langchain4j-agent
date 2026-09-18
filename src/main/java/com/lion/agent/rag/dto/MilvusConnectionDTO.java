package com.lion.agent.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Milvus 连接参数(页面"自定义连接"用)
 * <p>
 * 所有字段都可以不传: <b>为空时自动回退到 application.yml 的 {@code langchain4j.milvus.*} 默认值</b>,
 * 所以"用默认连接"的场景前端什么都不用带。
 */
@Data
@Schema(description = "Milvus 连接参数(为空则回退到 application.yml 的项目默认配置)")
public class MilvusConnectionDTO {

    @Schema(description = "Milvus 地址, 不传 = 用项目默认", example = "127.0.0.1")
    private String host;

    @Schema(description = "Milvus 端口, 不传 = 用项目默认", example = "19530")
    private Integer port;

    @Schema(description = "数据库名, 不传 = 用项目默认", example = "default")
    private String database;

    @Schema(description = "集合名, 不传 = 用项目默认", example = "lion_langchain4j_docs")
    private String collection;
}
