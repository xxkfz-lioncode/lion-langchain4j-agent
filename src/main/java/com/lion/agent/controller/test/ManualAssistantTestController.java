package com.lion.agent.controller.test;

import com.lion.agent.assistant.ManualAssistant;
import dev.langchain4j.service.TokenStream;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 编程式助手联调测试接口(免登录): 测试 {@link ManualAssistant} 手工装配的 AiServices。
 * <p>
 * 与 {@link TestController}(注解式 {@code @AiService}) 对照使用:
 * 两者底层模型/记忆/工具语义一致, 区别仅在于工具是通过 ToolSpecification + ToolExecutor
 * 编程注册, 而不是 {@code @Tool} 注解扫描。
 */
@Tag(name = "测试-编程式助手", description = "AiServices 手工装配(ToolSpecification + ToolExecutor 编程注册工具)的联调接口, 位于 controller.test 包, 免登录")
@RestController
@RequestMapping("/test/assistant")
@RequiredArgsConstructor
public class ManualAssistantTestController {

    /** SSE 流结束标记 */
    private static final String STREAM_DONE = "[DONE]";

    private final ManualAssistant manualAssistant;

    /** 阻塞式对话(整段返回) */
    @Operation(summary = "阻塞式对话(整段返回)",
            description = "等模型整段生成完再返回。底层工具为编程注册的 getCurrentTime / getTodayWeather(带熔断), "
                    + "问天气可观察熔断降级文案, 问时间验证无参工具调用。")
    @GetMapping("chat")
    public String chat(
            @Parameter(description = "用户消息", required = true, example = "北京今天天气怎么样")
            @RequestParam("msg") String msg) {
        return manualAssistant.chat("", msg);
    }

    /** 流式对话(SSE 逐 token 推送) */
    @Operation(summary = "流式对话(SSE 逐 token 推送)",
            description = "响应为 text/event-stream, 每个 data: 行推送一个 token, 结束推送 data: [DONE]。 "
                    + "浏览器直接访问即可看到逐字输出, 或用 curl 观察。")
    @GetMapping(value = "chatStream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter chatStream(
            @Parameter(description = "用户消息", required = true, example = "上海天气如何")
            @RequestParam("msg") String msg) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            TokenStream tokenStream = manualAssistant.chatStream("", msg);
            tokenStream
                    .onPartialResponse(partial -> send(emitter, partial))
                    .onCompleteResponse(response -> {
                        send(emitter, STREAM_DONE);
                        emitter.complete();
                    })
                    .onError(emitter::completeWithError)
                    .start();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /** 向 SSE 连接写入一个 data 事件; 连接断开时终止整个流 */
    private void send(SseEmitter emitter, String data) {
        try {
            emitter.send(SseEmitter.event().data(data));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }
}
