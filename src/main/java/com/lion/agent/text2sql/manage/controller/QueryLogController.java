package com.lion.agent.text2sql.manage.controller;

import com.lion.agent.common.PageResult;
import com.lion.agent.common.R;
import com.lion.agent.text2sql.entity.DataQueryLogEntity;
import com.lion.agent.text2sql.manage.service.QueryLogQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 问数日志查看接口(只读审计)。
 */
@Tag(name = "问数-调用日志", description = "问数全链路日志浏览(只读)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/text2sql/logs")
public class QueryLogController {

    private final QueryLogQueryService queryLogQueryService;

    @Operation(summary = "分页查询日志")
    @GetMapping
    public R<PageResult<DataQueryLogEntity>> page(
            @Parameter(description = "关键字(问题/生成 SQL)") @RequestParam(value = "keyword", required = false) String keyword,
            @Parameter(description = "结果状态(SUCCESS/EMPTY/TIMEOUT/ERROR)") @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return R.ok(queryLogQueryService.page(page, size, keyword, status));
    }
}
