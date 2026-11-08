package com.lion.agent.text2sql.manage.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lion.agent.common.PageResult;
import com.lion.agent.common.R;
import com.lion.agent.text2sql.entity.DataTermEntity;
import com.lion.agent.text2sql.manage.service.TermManageService;
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
 * 业务术语管理接口(运营配置页)。
 */
@Tag(name = "问数-术语管理", description = "业务术语/口径字典的增删改查")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/text2sql/terms")
public class TermController {

    private final TermManageService termManageService;

    @Operation(summary = "分页查询术语")
    @GetMapping
    public R<PageResult<DataTermEntity>> page(
            @Parameter(description = "关键字(术语/同义词/定义)") @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return R.ok(termManageService.page(page, size, keyword));
    }

    @Operation(summary = "新增术语")
    @PostMapping
    public R<DataTermEntity> create(@RequestBody DataTermEntity entity) {
        return R.ok(termManageService.create(entity, StpUtil.getLoginIdAsString()));
    }

    @Operation(summary = "更新术语")
    @PutMapping("/{id}")
    public R<DataTermEntity> update(@PathVariable Long id, @RequestBody DataTermEntity entity) {
        return R.ok(termManageService.update(id, entity));
    }

    @Operation(summary = "删除术语")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        termManageService.delete(id);
        return R.ok();
    }
}
