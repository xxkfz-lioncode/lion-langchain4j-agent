package com.lion.agent.guardrail;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailResult;
import lombok.extern.slf4j.Slf4j;

/**
 * 演示用输入护栏 B(排在 A 之后): 只打日志 + 放行。
 * <p>
 * 用途: 观察 A 的行为对它的影响 ——
 * <ul>
 *   <li>A 返回 success → B 正常执行, 日志出现</li>
 *   <li>A 返回 fatal → B 被短路, 日志不出现</li>
 * </ul>
 */
@Slf4j
public class GuardrailB implements InputGuardrail {

    @Override
    public InputGuardrailResult validate(UserMessage userMessage) {
        String text = userMessage.singleText();
        log.info("[输入护栏-B] 收到: {}", text);
        return success();
    }
}