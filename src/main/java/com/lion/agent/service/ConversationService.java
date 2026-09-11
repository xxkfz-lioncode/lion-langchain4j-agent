package com.lion.agent.service;

import com.lion.agent.pojo.vo.ConversationVO;

import java.util.List;

/**
 * 会话服务接口(会话元信息管理: 列表 / 新建 / 删除 / 标题自动更新)
 */
public interface ConversationService {

    /** 某用户的会话列表(按更新时间倒序) */
    List<ConversationVO> list(Long userId);

    /** 新建会话(默认标题"新对话") */
    ConversationVO create(Long userId);

    /**
     * 删除会话(仅限本人): 会同时清理对应 chat_message 与 langchain4j 内存记忆,
     * 由调用方(Controller)负责级联调用 {@link ChatService#clear(String)}。
     *
     * @return 是否存在并删除成功
     */
    boolean delete(Long userId, Long conversationId);

    /**
     * 若会话标题仍为默认值, 用首条用户消息前若干字替换, 使列表可读。
     * 发送首条消息时调用。
     */
    void renameIfDefault(Long conversationId, String text);
}
