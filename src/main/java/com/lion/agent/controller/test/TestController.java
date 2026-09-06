package com.lion.agent.controller.test;

import com.lion.agent.service.AgentAssistant;
import dev.langchain4j.service.TokenStream;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 联调测试接口(免登录): 位于 controller.test 包下, Sa-Token 拦截器自动放行。
 * <p>
 * 流式说明: {@link #chatStream(String)} 返回 SSE 事件流, 千问每生成一个 token
 * 即通过 data: 行推送; 结束推送 {@code data: [DONE]}。
 */
@RestController
@RequestMapping("/test")
@RequiredArgsConstructor
public class TestController {

    /** SSE 流结束标记 */
    private static final String STREAM_DONE = "[DONE]";

    private final AgentAssistant agentAssistant;

    /** 阻塞式对话(整段返回) */
    @GetMapping("chat")
    public String chat(@RequestParam("msg") String msg) {
        return agentAssistant.chat("", msg);
    }

    /** 流式对话(SSE 逐 token 推送) */
    @GetMapping(value = "chatStream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter chatStream(@RequestParam("msg") String msg) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            TokenStream tokenStream = agentAssistant.chatStream("", msg);
            // langchain4j 1.x TokenStream API: onPartialResponse / onCompleteResponse / onError
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
