package com.lion.agent.text2sql.manage.controller;

import com.lion.agent.common.PageResult;
import com.lion.agent.common.R;
import com.lion.agent.text2sql.entity.DataQueryFeedbackEntity;
import com.lion.agent.text2sql.manage.service.FeedbackManageService;
import com.lion.agent.text2sql.service.FeedbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 反馈记录管理接口(运营处理页)。
 * <p>
 * 列表查询在本控制器; 处理动作(标记已处理/沉淀示例)复用
 * {@link FeedbackService} 已有的 /api/text2sql/feedback/handle。
 */
@Tag(name = "问数-反馈管理", description = "用户反馈浏览与处理")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/text2sql/feedback")
public class FeedbackManageController {

    private final FeedbackManageService feedbackManageService;

    @Operation(summary = "分页查询反馈", description = "status 为空返回全部状态的反馈")
    @GetMapping("/list")
    public R<PageResult<DataQueryFeedbackEntity>> list(
            @Parameter(description = "处理状态(PENDING/HANDLED/IGNORED), 空为全部")
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return R.ok(feedbackManageService.page(page, size, status));
    }

    /** 供前端下拉使用的状态枚举 */
    @Operation(summary = "处理状态枚举")
    @GetMapping("/status-options")
    public R<List<String>> statusOptions() {
        return R.ok(List.of("PENDING", "HANDLED", "IGNORED"));
    }
}
