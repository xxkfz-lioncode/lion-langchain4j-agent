package com.lion.agent.model.service;

import com.lion.agent.model.dto.ModelConfigDTO;
import com.lion.agent.model.dto.ModelSiteUpdateDTO;
import com.lion.agent.model.vo.ModelConfigVO;

import java.util.List;
import java.util.Map;

/**
 * 模型配置管理: CRUD + 启停 + 设默认 + 连通性测试。
 * 业务侧二期接入 ModelRegistry; 本 service 是页面管理的唯一入口。
 */
public interface ModelConfigService {

    /** 配置列表(purpose 为空查全部); 默认在前, 其余按 id 倒序 */
    List<ModelConfigVO> list(String purpose);

    /** 新建(apiKey 必填) */
    Long create(ModelConfigDTO dto);

    /** 编辑(apiKey 留空 = 不修改密钥) */
    void update(Long id, ModelConfigDTO dto);

    /** 删除(默认模型禁止删除) */
    void delete(Long id);

    /** 启停(停用后业务回退系统默认模型) */
    void toggleEnabled(Long id, boolean enabled);

    /** 设为该用途下的默认模型(同用途唯一默认) */
    void setDefault(Long id);

    /**
     * 站点级更新: 按 baseUrl 批量同步该站点下所有模型的站点地址/密钥(事务内),
     * 密钥留空表示不改; 配置变更后状态重置为未测试、缓存失效。返回受影响模型数。
     */
    int updateSite(ModelSiteUpdateDTO dto);

    /**
     * 站点级删除: 按 baseUrl 批量删除该站点下所有模型(事务内, 含默认模型——
     * 删站是明确意图, 删除后业务回退系统默认配置)。返回删除条数。
     */
    int deleteSite(String baseUrl);

    /**
     * 连通性测试(不落库): 用传入的站点/密钥/模型名真实发一次最小对话请求,
     * 返回 { ok, elapsedMs, reply, error } —— 表单保存前就能验证配置有效性
     */
    Map<String, Object> testConnect(ModelConfigDTO dto);
}