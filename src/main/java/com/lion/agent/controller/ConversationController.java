package com.lion.agent.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lion.agent.common.Result;
import com.lion.agent.dto.ConversationVO;
import com.lion.agent.dto.MessageVO;
import com.lion.agent.service.ChatService;
import com.lion.agent.service.ConversationService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话历史管理接口(需登录): 会话列表 / 新建 / 删除 + 历史消息回放
 * <p>
 * 会话模型: 一个用户可建多个会话; 每个会话对应一个 memoryId(形如
 * {@code chat:{userId}:{conversationId}}), 由 ChatService 的 LangChain4j 记忆隔离。
 * 对话(发送/流式/清空)接口见 {@link ChatController}。
 */
@RestController
@RequestMapping("/api/chat")
public class ConversationController {

    private final ConversationService conversationService;
    private final ChatService chatService;

    public ConversationController(ConversationService conversationService, ChatService chatService) {
        this.conversationService = conversationService;
        this.chatService = chatService;
    }

    // ==================== 会话管理 ====================

    /** 当前用户的会话列表(按更新时间倒序) */
    @GetMapping("/conversations")
    public Result<List<ConversationVO>> conversations() {
        return Result.ok(conversationService.list(currentUserId()));
    }

    /** 新建会话(默认标题"新对话") */
    @PostMapping("/conversations")
    public Result<ConversationVO> createConversation() {
        return Result.ok(conversationService.create(currentUserId()));
    }

    /**
     * 删除会话(仅限本人): 清理会话元信息、对应 chat_message 记录及 langchain4j 内存记忆
     */
    @DeleteMapping("/conversations/{id}")
    public Result<Void> deleteConversation(@PathVariable("id") Long conversationId) {
        Long userId = currentUserId();
        if (conversationService.delete(userId, conversationId)) {
            chatService.clear(buildMemoryId(userId, conversationId));
        }
        return Result.ok();
    }

    // ==================== 历史消息 ====================

    /**
     * 某会话的历史消息(过滤系统提示, 按先后顺序返回, 供切换会话时回放)
     */
    @GetMapping("/messages")
    public Result<List<MessageVO>> messages(@RequestParam("conversationId") Long conversationId) {
        return Result.ok(chatService.listMessages(buildMemoryId(currentUserId(), conversationId)));
    }

    /** 当前登录用户id */
    private Long currentUserId() {
        return Long.valueOf(StpUtil.getLoginIdAsString());
    }

    /** 拼装 langchain4j 会话记忆 id */
    private String buildMemoryId(Long userId, Long conversationId) {
        return "chat:" + userId + ":" + conversationId;
    }
}
