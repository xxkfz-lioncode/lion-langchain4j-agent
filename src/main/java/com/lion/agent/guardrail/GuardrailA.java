package com.lion.agent.guardrail;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailResult;
import lombok.extern.slf4j.Slf4j;

/**
 * 演示用输入护栏 A(排在链的最前面): 验证"声明顺序 = 执行顺序"与 fatal 短路。
 * <p>
 * 默认只打日志 + 放行; 想验证 fatal 短路时, 把 {@code MODE} 切到 {@code FATAL},
 * 则排在它后面的 LoggingInputGuardrail / GuardrailB 的日志将不再出现(链被它掐断)。
 */
@Slf4j
public class GuardrailA implements InputGuardrail {

    /** 演示模式开关: LOG=只打日志放行(默认), FATAL=命中即 fatal 短路后面的护栏 */
    public enum Mode { LOG, FATAL }

    private final Mode mode;

    public GuardrailA() {
        this(Mode.LOG);
    }

    public GuardrailA(Mode mode) {
        this.mode = mode;
    }

    @Override
    public InputGuardrailResult validate(UserMessage userMessage) {
        String text = userMessage.singleText();
        log.info("[输入护栏-A] mode={}, 收到: {}", mode, text);
        if (mode == Mode.FATAL) {
            // fatal: 链立即中断, 后面所有护栏都不再执行
            return fatal("护栏A演示 fatal 短路");
        }
        return success();
    }
}