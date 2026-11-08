package com.lion.agent.text2sql.manage.controller;

import com.lion.agent.common.PageResult;
import com.lion.agent.common.R;
import com.lion.agent.text2sql.entity.DataMaskRuleEntity;
import com.lion.agent.text2sql.manage.service.MaskRuleManageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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

/**
 * 脱敏规则管理接口(安全配置页)。
 */
@Tag(name = "问数-脱敏规则", description = "问数结果脱敏规则的增删改查, 保存后即时生效")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/text2sql/mask-rules")
public class MaskRuleController {

    private final MaskRuleManageService maskRuleManageService;

    @Operation(summary = "分页查询规则")
    @GetMapping
    public R<PageResult<DataMaskRuleEntity>> page(
            @Parameter(description = "关键字(表名/字段名)") @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return R.ok(maskRuleManageService.page(page, size, keyword));
    }

    @Operation(summary = "新增规则")
    @PostMapping
    public R<DataMaskRuleEntity> create(@RequestBody DataMaskRuleEntity entity) {
        return R.ok(maskRuleManageService.create(entity));
    }

    @Operation(summary = "更新规则")
    @PutMapping("/{id}")
    public R<DataMaskRuleEntity> update(@PathVariable Long id, @RequestBody DataMaskRuleEntity entity) {
        return R.ok(maskRuleManageService.update(id, entity));
    }

    @Operation(summary = "删除规则")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        maskRuleManageService.delete(id);
        return R.ok();
    }
}
