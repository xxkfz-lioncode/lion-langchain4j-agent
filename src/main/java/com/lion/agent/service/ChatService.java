package com.lion.agent.service;

import com.lion.agent.pojo.vo.MessageVO;
import dev.langchain4j.service.TokenStream;

import java.util.List;

/**
 * 对话服务接口
 * <p>
 * 实现基于 LangChain4j @AiService 注解式智能助手,
 * 会话记忆按 memoryId(登录用户) 隔离。
 */
public interface ChatService {

    /**
     * 发送一条用户消息并等待 AI 完整回复(阻塞)
     *
     * @param memoryId    会话记忆 id(通常是 "chat:" + 登录用户id)
     * @param userMessage 用户消息
     * @return AI 回复文本
     */
    String chat(String memoryId, String userMessage);

    /**
     * 发送一条用户消息并返回流式 {@link TokenStream}(打字机效果)
     *
     * @param memoryId    会话记忆 id(通常是 "chat:" + 登录用户id)
     * @param userMessage 用户消息
     * @return TokenStream, 由控制器注册回调并 start()
     */
    TokenStream chatStream(String memoryId, String userMessage);

    /** 清空某用户的会话记忆 */
    void clear(String memoryId);

    /**
     * 查询某会话的历史消息(过滤系统提示, 按先后顺序排列)
     *
     * @param memoryId 会话记忆 id(如 chat:1:12)
     * @return 会话历史消息
     */
    List<MessageVO> listMessages(String memoryId);
}
