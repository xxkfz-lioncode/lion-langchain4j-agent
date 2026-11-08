package com.lion.agent.text2sql.manage.controller;

import com.lion.agent.common.PageResult;
import com.lion.agent.common.R;
import com.lion.agent.text2sql.entity.DataFewshotEntity;
import com.lion.agent.text2sql.manage.service.FewshotManageService;
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
 * Few-shot 示例库管理接口。
 */
@Tag(name = "问数-示例库", description = "标准问答对(问题 -> SQL)的增删改查, 启用的示例会注入 SQL 生成 prompt")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/text2sql/fewshots")
public class FewshotController {

    private final FewshotManageService fewshotManageService;

    @Operation(summary = "分页查找示例")
    @GetMapping
    public R<PageResult<DataFewshotEntity>> page(
            @Parameter(description = "关键字(问题/口径说明/涉及表)") @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return R.ok(fewshotManageService.page(page, size, keyword));
    }

    @Operation(summary = "新增示例")
    @PostMapping
    public R<DataFewshotEntity> create(@RequestBody DataFewshotEntity entity) {
        return R.ok(fewshotManageService.create(entity));
    }

    @Operation(summary = "更新示例")
    @PutMapping("/{id}")
    public R<DataFewshotEntity> update(@PathVariable Long id, @RequestBody DataFewshotEntity entity) {
        return R.ok(fewshotManageService.update(id, entity));
    }

    @Operation(summary = "删除示例")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        fewshotManageService.delete(id);
        return R.ok();
    }
}
