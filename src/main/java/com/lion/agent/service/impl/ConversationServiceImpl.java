package com.lion.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lion.agent.dto.ConversationVO;
import com.lion.agent.entity.ChatConversationEntity;
import com.lion.agent.mapper.ChatConversationMapper;
import com.lion.agent.service.ConversationService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 会话服务实现(基于 chat_conversation 表)
 */
@Service
public class ConversationServiceImpl implements ConversationService {

    /** 默认会话标题 */
    private static final String DEFAULT_TITLE = "新对话";

    /** 用首条消息作为标题时截取的最大长度 */
    private static final int TITLE_MAX = 20;

    private final ChatConversationMapper conversationMapper;

    public ConversationServiceImpl(ChatConversationMapper conversationMapper) {
        this.conversationMapper = conversationMapper;
    }

    @Override
    public List<ConversationVO> list(Long userId) {
        List<ChatConversationEntity> rows = conversationMapper.selectList(
                new LambdaQueryWrapper<ChatConversationEntity>()
                        .eq(ChatConversationEntity::getUserId, userId)
                        .orderByDesc(ChatConversationEntity::getUpdateTime));
        return rows.stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public ConversationVO create(Long userId) {
        ChatConversationEntity entity = new ChatConversationEntity();
        entity.setUserId(userId);
        entity.setTitle(DEFAULT_TITLE);
        conversationMapper.insert(entity);
        // 重新查询一次以拿到数据库自动生成的 create_time / update_time
        return toVO(conversationMapper.selectById(entity.getId()));
    }

    @Override
    public boolean delete(Long userId, Long conversationId) {
        int rows = conversationMapper.delete(
                new LambdaQueryWrapper<ChatConversationEntity>()
                        .eq(ChatConversationEntity::getId, conversationId)
                        .eq(ChatConversationEntity::getUserId, userId));
        return rows > 0;
    }

    @Override
    public void renameIfDefault(Long conversationId, String text) {
        if (conversationId == null || text == null || text.isBlank()) {
            return;
        }
        ChatConversationEntity existed = conversationMapper.selectById(conversationId);
        if (existed == null || !DEFAULT_TITLE.equals(existed.getTitle())) {
            return;
        }
        ChatConversationEntity update = new ChatConversationEntity();
        update.setId(conversationId);
        update.setTitle(buildTitle(text));
        conversationMapper.updateById(update);
    }

    /** 截取消息开头作为标题 */
    private String buildTitle(String text) {
        String trimmed = text.trim();
        return trimmed.length() > TITLE_MAX ? trimmed.substring(0, TITLE_MAX) + "…" : trimmed;
    }

    private ConversationVO toVO(ChatConversationEntity e) {
        if (e == null) {
            return null;
        }
        return new ConversationVO(e.getId(), e.getTitle(), e.getCreateTime(), e.getUpdateTime());
    }
}
