package com.lion.agent.model.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 模型配置新建/编辑请求。
 * <p>
 * key 规则: <b>编辑时 apiKey 留空 = 不修改原密钥</b>(新建时必填, 由 service 校验),
 * 后端任何接口都不回传 key 明文。
 */
@Data
public class ModelConfigDTO {

    /** 配置 id: 仅连通性测试复用库里已存密钥时使用(create/update 路径不传, id 走 @PathVariable) */
    private Long id;

    /**
     * 复制新增专用: 从该 id 的配置复用 API Key 密文(明文不回显、不需用户重填)。
     * 与 apiKey 互斥: apiKey 有值时以其为准; apiKey 为空且本字段非空时走密文复制。
     */
    private Long copyKeyFrom;

    /** 展示名(唯一) */
    @NotBlank(message = "名称不能为空")
    @Size(max = 64, message = "名称最长 64 字符")
    private String name;

    /** 协议族, 一期固定 openai-compatible */
    @NotBlank(message = "协议族不能为空")
    @Pattern(regexp = "openai-compatible", message = "一期仅支持 openai-compatible 协议族")
    private String provider = "openai-compatible";

    /** 站点地址(OpenAI 兼容入口) */
    @NotBlank(message = "站点地址不能为空")
    @Pattern(regexp = "^https?://.+", message = "站点地址必须是 http(s):// 开头的 URL")
    @Size(max = 255)
    private String baseUrl;

    /**
     * API Key: 新建必填; 编辑留空 = 不修改
     * (无 @NotBlank: 由 service 按新建/编辑分别校验)
     */
    private String apiKey;

    /** 模型名 */
    @NotBlank(message = "模型名不能为空")
    @Size(max = 64)
    private String modelName;

    /** 用途: chat(一期固定, 二期扩展 embedding/rerank/image) */
    @Pattern(regexp = "chat|embedding|rerank|image", message = "用途必须是 chat/embedding/rerank/image")
    private String purpose = "chat";

    /** 温度(0~2) */
    @DecimalMin(value = "0", message = "温度不能小于 0")
    @DecimalMax(value = "2", message = "温度不能大于 2")
    private Double temperature;

    /** 超时(ms) */
    private Integer timeoutMs;

    /** 备注 */
    @Size(max = 255, message = "备注最长 255 字符")
    private String remark;
}