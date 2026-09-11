package com.lion.agent.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lion.agent.common.exception.BusinessException;
import com.lion.agent.common.Result;
import com.lion.agent.pojo.dto.ChatReply;
import com.lion.agent.pojo.dto.ChatRequest;
import com.lion.agent.service.ChatService;
import com.lion.agent.service.ConversationService;
import dev.langchain4j.service.TokenStream;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 对话接口(需登录): 发送 / 流式 / 清空上下文
 * <p>
 * 会话模型: 一个用户可建多个会话; 每个会话对应一个 memoryId(形如
 * {@code chat:{userId}:{conversationId}}), 由 ChatService 的 LangChain4j 记忆隔离。
 * 会话管理(列表/新建/删除)与历史消息回放接口见 {@link ConversationController}。
 * <p>
 * 流式说明: {@link #stream(String, Long)} 返回 SSE(Server-Sent Events)事件流,
 * 千问每生成一个 token 即通过 data: 行推送给前端; 结束推送 {@code data: [DONE]}。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    /** SSE 流结束标记(前端据此判定一轮回复结束) */
    private static final String STREAM_DONE = "[DONE]";

    private final ChatService chatService;
    private final ConversationService conversationService;

    public ChatController(ChatService chatService, ConversationService conversationService) {
        this.chatService = chatService;
        this.conversationService = conversationService;
    }

    // ==================== 对话 ====================

    /**
     * 发送消息并获取千问回复(阻塞式)。会话为空时自动新建。
     */
    @PostMapping("/send")
    public Result<ChatReply> send(@RequestBody @Valid ChatRequest request) {
        Long userId = currentUserId();
        Long conversationId = ensureConversation(userId, request.getConversationId());
        conversationService.renameIfDefault(conversationId, request.getMessage());
        String memoryId = buildMemoryId(userId, conversationId);
        String reply = chatService.chat(memoryId, request.getMessage());
        return Result.ok(new ChatReply(reply));
    }

    /**
     * 流式对话: SSE 推送千问逐 token 回复(按登录用户 + 会话隔离上下文)。
     * <p>
     * 事件格式: text/event-stream, 每行一个 {@code data: <token>},
     * 结束时发送 {@code data: [DONE]}。会话为空时自动新建。
     *
     * @param message        用户消息(经 URL 编码传入)
     * @param conversationId 会话id(为空时自动新建)
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("message") String message,
                             @RequestParam(value = "conversationId", required = false) Long conversationId) {
        if (!StringUtils.hasText(message)) {
            throw new BusinessException("消息内容不能为空");
        }
        Long userId = currentUserId();
        Long finalConversationId = ensureConversation(userId, conversationId);
        conversationService.renameIfDefault(finalConversationId, message);
        String memoryId = buildMemoryId(userId, finalConversationId);

        SseEmitter emitter = new SseEmitter(0L);
        try {
            TokenStream tokenStream = chatService.chatStream(memoryId, message.trim());
            // langchain4j 1.x 的 TokenStream API: onPartialResponse(token) / onCompleteResponse(response) / onError
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

    /** 清空当前指定会话的上下文 */
    @PostMapping("/clear")
    public Result<Void> clear(@RequestParam(value = "conversationId", required = false) Long conversationId) {
        Long userId = currentUserId();
        Long finalConversationId = ensureConversation(userId, conversationId);
        chatService.clear(buildMemoryId(userId, finalConversationId));
        return Result.ok();
    }

    /** 向 SSE 连接写入一个 data 事件; 连接断开时终止整个流 */
    private void send(SseEmitter emitter, String data) {
        try {
            emitter.send(SseEmitter.event().data(data));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    /** 当前登录用户id */
    private Long currentUserId() {
        return Long.valueOf(StpUtil.getLoginIdAsString());
    }

    /** 拼装 langchain4j 会话记忆 id */
    private String buildMemoryId(Long userId, Long conversationId) {
        return "chat:" + userId + ":" + conversationId;
    }

    /** 会话为空时自动新建, 保证始终返回一个有效会话id */
    private Long ensureConversation(Long userId, Long conversationId) {
        if (conversationId == null) {
            return conversationService.create(userId).getId();
        }
        return conversationId;
    }
}
