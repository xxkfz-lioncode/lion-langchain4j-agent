package com.lion.agent.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 站点级更新请求(主从视图里"编辑站点"用):
 * 按 baseUrl 定位该站点下的全部模型, 批量同步站点地址/密钥, 避免同一站点出现不一致的 URL 或 key。
 */
@Data
public class ModelSiteUpdateDTO {

    /** 原站点地址(定位用) */
    @NotBlank(message = "原站点地址不能为空")
    private String baseUrl;

    /** 新站点地址(可与原值相同; 改了则该站点下所有模型一并迁移) */
    @NotBlank(message = "站点地址不能为空")
    @Pattern(regexp = "^https?://.+", message = "站点地址必须是 http(s):// 开头的 URL")
    private String newBaseUrl;

    /** 新 API Key(留空 = 保持原密钥不变) */
    private String apiKey;
}