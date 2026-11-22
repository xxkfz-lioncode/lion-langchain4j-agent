package com.lion.agent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型配置表(t_model_config):
 * 页面化的 LLM 接入配置(站点/密钥/模型名/参数), 支持运行时新增与切换,
 * 替代"改 application.yml + 重启"的传统方式。api_key 以 AES 加密落库, 永不明文存储。
 * <p>
 * 一期仅接入 chat(对话)用途; embedding/rerank/image 为二期扩展(purpose 字段已预留)。
 * 业务侧通过 {@link com.lion.agent.model.ModelRegistry} 按本表配置动态构建模型实例。
 */
@Data
@TableName("t_model_config")
public class ModelConfigEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 展示名(唯一), 如: 千问-plus-生产 */
    private String name;

    /** 协议族: openai-compatible(千问兼容模式/DeepSeek/Kimi/GLM/Ollama 等) */
    private String provider;

    /** 站点地址(OpenAI 兼容入口), 如: https://dashscope.aliyuncs.com/compatible-mode/v1 */
    private String baseUrl;

    /** API Key(AES 加密密文, 明文永不落库) */
    private String apiKeyCipher;

    /** 模型名, 如: qwen-plus / deepseek-v3 */
    private String modelName;

    /** 用途: chat / embedding / rerank / image(一期仅 chat) */
    private String purpose;

    /** 温度(可空=用模型默认) */
    private Double temperature;

    /** 请求超时(ms) */
    private Integer timeoutMs;

    /** 启停: 1 启用 / 0 停用(停用后业务回退系统默认模型) */
    private Integer enabled;

    /** 同用途下的默认模型: 1 是 / 0 否(每个 purpose 至多一条) */
    private Integer isDefault;

    /** 最近一次连通测试结果: untested / ok / fail */
    private String status;

    /** 备注 */
    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
