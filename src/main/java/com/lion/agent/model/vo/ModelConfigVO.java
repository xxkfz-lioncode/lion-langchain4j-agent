package com.lion.agent.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型配置响应 VO: <b>api_key 只出脱敏形态(前3后4), 明文/密文永不回传前端</b>。
 */
@Data
public class ModelConfigVO {

    private Long id;

    private String name;

    private String provider;

    /** 站点地址(非敏感, 明文展示) */
    private String baseUrl;

    /** 脱敏后的 key, 如: sk-****7f3a */
    private String apiKeyMasked;

    private String modelName;

    private String purpose;

    private Double temperature;

    private Integer timeoutMs;

    /** 1 启用 / 0 停用 */
    private Integer enabled;

    /** 1 默认 / 0 非默认 */
    private Integer isDefault;

    /** untested / ok / fail */
    private String status;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** key 脱敏: 前 3 + **** + 后 4; 过短全打码 */
    public static String mask(String plainKey) {
        if (plainKey == null || plainKey.isBlank()) {
            return "";
        }
        String k = plainKey.trim();
        if (k.length() <= 8) {
            return "****";
        }
        return k.substring(0, 3) + "****" + k.substring(k.length() - 4);
    }
}