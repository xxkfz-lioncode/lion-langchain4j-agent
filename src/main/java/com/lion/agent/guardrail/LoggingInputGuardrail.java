package com.lion.agent.guardrail;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailResult;
import lombok.extern.slf4j.Slf4j;

/**
 * 输入护栏（骨架版）：仅打印 trace 日志，默认放行。
 * <p>
 * <b>触发时机</b>：LLM 调用前的最后一个拦截点（RAG 检索完成之后）。
 * 链中第一个 {@code fatal} 会立刻抛出 {@code InputGuardrailException} 阻断调用；
 * {@code failure} 则让链继续跑完、累积多个问题后统一抛异常。
 * <p>
 * <b>当前行为</b>：不做任何校验，直接 {@code success()} 放行 —— 仅用于让护栏链跑起来，
 * 观察触发时机与日志格式。
 * <p>
 * <b>扩展位置</b>：把 {@code validate()} 中的 {@code return success();} 替换成真正的业务校验。
 * <pre>{@code
 *   String lower = text.toLowerCase();
 *   if (lower.contains("忽略之前的指令")) {
 *       return fatal("检测到提示注入");
 *   }
 *   return success();
 * }</pre>
 *
 * @see dev.langchain4j.guardrail.InputGuardrail
 */
@Slf4j
public class LoggingInputGuardrail implements InputGuardrail {

    @Override
    public InputGuardrailResult validate(UserMessage userMessage) {
        String text = userMessage.singleText();
        int len = text == null ? 0 : text.length();
        log.error("[输入护栏] userMessage 长度={}, 前 30 字={}", len, abbreviate(text));
        // TODO 业务校验：命中返回 fatal("理由"); 多个问题累积用 failure("理由")
        return success();
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 30 ? s : s.substring(0, 30) + "...";
    }
}