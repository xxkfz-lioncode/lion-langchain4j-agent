package com.lion.agent.model.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.lion.agent.common.R;
import com.lion.agent.model.dto.ModelConfigDTO;
import com.lion.agent.model.dto.ModelSiteUpdateDTO;
import com.lion.agent.model.service.ModelConfigService;
import com.lion.agent.model.vo.ModelConfigVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 模型管理接口(仅管理员): 页面化维护 LLM 接入配置(站点/密钥/模型名/参数),
 * 支持连通性测试与默认模型切换; 一期只做配置管理与测试, 业务链路二期接入。
 * <p>
 * 安全: 类级 {@code @SaCheckRole("admin")} 与用户管理同策略; key 明文永不回传(VO 已脱敏)。
 */
@Tag(name = "模型管理", description = "模型配置 CRUD / 启停 / 设默认 / 连通性测试(仅 admin)")
@SaCheckRole("admin")
@RestController
@RequestMapping("/api/model-configs")
@RequiredArgsConstructor
public class ModelConfigController {

    private final ModelConfigService service;

    @Operation(summary = "配置列表", description = "默认模型排最前; apiKey 只返回脱敏形态")
    @GetMapping
    public R<List<ModelConfigVO>> list(@RequestParam(required = false) String purpose) {
        return R.ok(service.list(purpose));
    }

    @Operation(summary = "新建配置")
    @PostMapping
    public R<Long> create(@Valid @RequestBody ModelConfigDTO dto) {
        return R.ok(service.create(dto));
    }

    @Operation(summary = "编辑配置", description = "apiKey 留空 = 不修改原密钥")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody ModelConfigDTO dto) {
        service.update(id, dto);
        return R.ok();
    }

    @Operation(summary = "删除配置", description = "默认模型禁止删除")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }

    @Operation(summary = "启停配置", description = "停用后业务自动回退系统默认模型")
    @PutMapping("/{id}/enabled")
    public R<Void> toggleEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        service.toggleEnabled(id, enabled);
        return R.ok();
    }

    @Operation(summary = "设为默认", description = "同用途下唯一默认")
    @PutMapping("/{id}/default")
    public R<Void> setDefault(@PathVariable Long id) {
        service.setDefault(id);
        return R.ok();
    }

    @Operation(summary = "编辑站点", description = "按站点地址批量同步该站点下所有模型的 URL/密钥(事务)")
    @PutMapping("/site")
    public R<Integer> updateSite(@Valid @RequestBody ModelSiteUpdateDTO dto) {
        return R.ok(service.updateSite(dto));
    }

    @Operation(summary = "删除站点", description = "批量删除该站点下所有模型(含默认模型, 删除后业务回退系统默认)")
    @DeleteMapping("/site")
    public R<Integer> deleteSite(@RequestParam String baseUrl) {
        return R.ok(service.deleteSite(baseUrl));
    }

    @Operation(summary = "连通性测试", description = "真实发一次最小对话请求验证站点/密钥/模型名, 不落库")
    @PostMapping("/test")
    public R<Map<String, Object>> testConnect(@RequestBody ModelConfigDTO dto) {
        return R.ok(service.testConnect(dto));
    }
}