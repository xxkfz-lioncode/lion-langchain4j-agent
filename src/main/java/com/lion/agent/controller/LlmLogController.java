package com.lion.agent.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lion.agent.ai.observability.LlmCallLog;
import com.lion.agent.ai.observability.LlmCallLogMapper;
import com.lion.agent.common.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 调用日志接口(需登录): 分页查询 + 汇总统计。
 * <p>
 * 数据来源: {@code LoggingChatModelListener} 异步落库的 llm_call_log 表(调用审计,
 * 与 chat_message 记忆表分离)。权限:
 * <ul>
 *   <li>普通用户 —— 只能看自己的调用记录(服务端强制按 userId 过滤);</li>
 *   <li>admin —— 可看全部, 并可按 userId / bizId / 成败筛选。</li>
 * </ul>
 */
@Tag(name = "LLM 调用日志", description = "模型调用审计(模型/Token/耗时/成败)分页与统计(需登录; 普通用户仅见本人, admin 可查全部)")
@RestController
@RequestMapping("/api/log/llm")
public class LlmLogController {

    private final LlmCallLogMapper llmCallLogMapper;

    public LlmLogController(LlmCallLogMapper llmCallLogMapper) {
        this.llmCallLogMapper = llmCallLogMapper;
    }

    /** 分页查询调用日志(按 id 倒序, 即最新在前) */
    @Operation(summary = "分页查询 LLM 调用日志", description = "按 id 倒序; 普通用户仅返回本人记录, admin 可查全部并可按条件筛选")
    @GetMapping("/page")
    public R<Page<LlmCallLog>> page(
            @Parameter(description = "页码", example = "1") @RequestParam(defaultValue = "1") long current,
            @Parameter(description = "每页条数", example = "10") @RequestParam(defaultValue = "10") long size,
            @Parameter(description = "业务追踪id(一次提问一个, 串起多轮工具调用)") @RequestParam(required = false) String bizId,
            @Parameter(description = "成败过滤: true=成功 / false=失败") @RequestParam(required = false) Boolean success,
            @Parameter(description = "用户id(仅 admin 可用)") @RequestParam(required = false) Long userId) {
        QueryWrapper<LlmCallLog> wrapper = new QueryWrapper<>();
        // 非 admin 强制只看自己; admin 未指定 userId 时看全部
        if (!StpUtil.hasRole("admin")) {
            wrapper.eq("user_id", currentUserId());
        } else if (userId != null) {
            wrapper.eq("user_id", userId);
        }
        if (StringUtils.hasText(bizId)) {
            wrapper.eq("biz_id", bizId.trim());
        }
        if (success != null) {
            wrapper.eq("success", success ? 1 : 0);
        }
        wrapper.orderByDesc("id");
        return R.ok(llmCallLogMapper.selectPage(new Page<>(current, size), wrapper));
    }

    /** 汇总统计: 总调用数 / 成功数 / 总 Token / 平均耗时(查询范围同分页接口) */
    @Operation(summary = "调用汇总统计", description = "总调用数 / 成功数 / 总Token / 平均耗时; 范围同分页接口(普通用户仅本人)")
    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        QueryWrapper<LlmCallLog> wrapper = new QueryWrapper<>();
        if (!StpUtil.hasRole("admin")) {
            wrapper.eq("user_id", currentUserId());
        }
        wrapper.select(
                "COUNT(*) AS `totalCalls`",
                "IFNULL(SUM(success), 0) AS `successCalls`",
                "IFNULL(SUM(total_tokens), 0) AS `totalTokens`",
                "IFNULL(ROUND(AVG(CASE WHEN success = 1 THEN duration_ms END)), 0) AS `avgDurationMs`");
        List<Map<String, Object>> rows = llmCallLogMapper.selectMaps(wrapper);
        Map<String, Object> raw = rows.isEmpty() ? Map.of() : rows.get(0);

        // 统一转成前端好用的类型; 空表时给默认值
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCalls", toLong(raw.get("totalCalls")));
        result.put("successCalls", toLong(raw.get("successCalls")));
        result.put("totalTokens", toLong(raw.get("totalTokens")));
        result.put("avgDurationMs", toLong(raw.get("avgDurationMs")));
        return R.ok(result);
    }

    /** 聚合查询结果统一转 Long(MySQL 聚合可能返回 BigInteger/BigDecimal) */
    private Long toLong(Object value) {
        return value == null ? 0L : Long.valueOf(value.toString());
    }

    /** 当前登录用户id */
    private Long currentUserId() {
        return Long.valueOf(StpUtil.getLoginIdAsString());
    }
}
