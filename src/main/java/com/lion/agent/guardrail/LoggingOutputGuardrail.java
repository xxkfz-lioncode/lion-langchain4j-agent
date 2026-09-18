package com.lion.agent.guardrail;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import lombok.extern.slf4j.Slf4j;

/**
 * 输出护栏（骨架版）：仅打印 trace 日志，默认放行。
 * <p>
 * <b>触发时机</b>：LLM 返回 {@code AiMessage} 之后（所有工具调用已完成），是面向调用者的最后一道关卡。
 * 流式（{@code TokenStream}）场景下，护栏在流结束后、{@code onCompleteResponse} 回调时执行；
 * 若触发 {@code reprompt} 且最终成功，整条护栏链会从头同步重跑。
 * <p>
 * <b>当前行为</b>：不做任何校验，直接 {@code success()} 放行 —— 仅用于让护栏链跑起来。
 * <p>
 * <b>扩展位置</b>：把 {@code validate()} 中的 {@code return success();} 替换成真正的业务校验。
 * 命中后可选返回：
 * <ul>
 *   <li>{@code successWith(text)} —— 把输出重写后放行</li>
 *   <li>{@code reprompt("原因", "新指令")} —— 追加新指令让 LLM 自愈重答（推荐）</li>
 *   <li>{@code fatal("原因")} —— 直接拒绝返回</li>
 * </ul>
 *
 * @see dev.langchain4j.guardrail.OutputGuardrail
 */
@Slf4j
public class LoggingOutputGuardrail implements OutputGuardrail {

    @Override
    public OutputGuardrailResult validate(AiMessage responseFromLLM) {
        String text = responseFromLLM.text();
        int len = text == null ? 0 : text.length();
        log.error("[输出护栏] aiMessage 长度={}, 前 30 字={}", len, abbreviate(text));
        // TODO 业务校验：见类注释, 在 successWith / reprompt / fatal 中按需选用
//        return reprompt("强制重答", "请换个说法");
        return success();
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 30 ? s : s.substring(0, 30) + "...";
    }
}