package com.lion.agent.text2sql.manage.controller;

import com.lion.agent.common.PageResult;
import com.lion.agent.common.R;
import com.lion.agent.text2sql.entity.BizOrderEntity;
import com.lion.agent.text2sql.manage.service.OrderQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单数据查看接口(只读)。
 */
@Tag(name = "问数-订单数据", description = "业务订单数据浏览(只读, 不提供写入入口)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/text2sql/orders")
public class OrderController {

    private final OrderQueryService orderQueryService;

    @Operation(summary = "分页查询订单")
    @GetMapping
    public R<PageResult<BizOrderEntity>> page(
            @Parameter(description = "关键字(订单号/下单用户/商品名称)") @RequestParam(value = "keyword", required = false) String keyword,
            @Parameter(description = "下单城市") @RequestParam(value = "city", required = false) String city,
            @Parameter(description = "订单状态(1~5)") @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return R.ok(orderQueryService.page(page, size, keyword, city, status));
    }
}
