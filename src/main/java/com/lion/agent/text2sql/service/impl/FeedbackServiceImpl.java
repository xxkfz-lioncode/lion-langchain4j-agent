package com.lion.agent.text2sql.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lion.agent.common.PageResult;
import com.lion.agent.text2sql.entity.DataFewshotEntity;
import com.lion.agent.text2sql.entity.DataQueryFeedbackEntity;
import com.lion.agent.text2sql.entity.DataQueryLogEntity;
import com.lion.agent.text2sql.mapper.DataFewshotMapper;
import com.lion.agent.text2sql.mapper.DataQueryFeedbackMapper;
import com.lion.agent.text2sql.mapper.DataQueryLogMapper;
import com.lion.agent.text2sql.pojo.dto.FeedbackRequest;
import com.lion.agent.text2sql.pojo.vo.BadCaseVO;
import com.lion.agent.text2sql.service.FeedbackService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 反馈与 bad case 闭环实现。
 * <pre>
 * 用户踩 + 纠错 SQL
 *    → data_query_feedback(PENDING)
 *    → 运营分析根因
 *        ├─ schema 不清  → 更新 data_term / 触发字段增强
 *        ├─ SQL 生成错  → promoteToFewshot=true 写入 data_fewshot
 *        └─ 口径问题    → 更新 data_term.calc_rule
 *    → data_query_feedback(HANDLED)
 * </pre>
 */
@Slf4j
@Service
public class FeedbackServiceImpl implements FeedbackService {

    private final DataQueryFeedbackMapper feedbackMapper;
    private final DataQueryLogMapper queryLogMapper;
    private final DataFewshotMapper fewshotMapper;

    public FeedbackServiceImpl(DataQueryFeedbackMapper feedbackMapper,
                               DataQueryLogMapper queryLogMapper,
                               DataFewshotMapper fewshotMapper) {
        this.feedbackMapper = feedbackMapper;
        this.queryLogMapper = queryLogMapper;
        this.fewshotMapper = fewshotMapper;
    }

    @Override
    public void submit(FeedbackRequest request, Long userId) {
        if (!StringUtils.hasText(request.getBizId())) {
            throw new IllegalArgumentException("bizId 不能为空");
        }
        // 一个 bizId 只允许一条反馈(可重复提交则覆盖)
        DataQueryFeedbackEntity entity = feedbackMapper.selectOne(
                new LambdaQueryWrapper<DataQueryFeedbackEntity>()
                        .eq(DataQueryFeedbackEntity::getBizId, request.getBizId()));
        if (entity == null) {
            entity = new DataQueryFeedbackEntity();
            entity.setBizId(request.getBizId());
            entity.setUserId(userId);
            entity.setHandleStatus("PENDING");
        }
        entity.setRating(request.getRating());
        entity.setCorrectSql(request.getCorrectSql());
        entity.setComment(request.getComment());
        if (entity.getId() == null) {
            feedbackMapper.insert(entity);
        } else {
            feedbackMapper.updateById(entity);
        }
        log.info("[text2sql] 收到反馈 bizId={}, rating={}, userId={}",
                request.getBizId(), request.getRating(), userId);
    }

    @Override
    public PageResult<BadCaseVO> listBadCases(int page, int size) {
        Page<DataQueryFeedbackEntity> p = feedbackMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<DataQueryFeedbackEntity>()
                        .eq(DataQueryFeedbackEntity::getHandleStatus, "PENDING")
                        .orderByDesc(DataQueryFeedbackEntity::getCreateTime));

        List<BadCaseVO> records = new ArrayList<>();
        for (DataQueryFeedbackEntity f : p.getRecords()) {
            BadCaseVO vo = new BadCaseVO();
            vo.setId(f.getId());
            vo.setBizId(f.getBizId());
            vo.setUserId(f.getUserId());
            vo.setRating(f.getRating());
            vo.setCorrectSql(f.getCorrectSql());
            vo.setComment(f.getComment());
            vo.setHandleStatus(f.getHandleStatus());
            vo.setCreateTime(f.getCreateTime());

            DataQueryLogEntity logEntity = queryLogMapper.selectOne(
                    new LambdaQueryWrapper<DataQueryLogEntity>()
                            .eq(DataQueryLogEntity::getBizId, f.getBizId()));
            if (logEntity != null) {
                vo.setQuestion(logEntity.getQuestion());
                vo.setGeneratedSql(logEntity.getGeneratedSql());
            }
            records.add(vo);
        }
        return PageResult.of(p.getTotal(), records);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markHandled(Long feedbackId, boolean promoteToFewshot) {
        updateStatus(feedbackId, "HANDLED", promoteToFewshot);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long feedbackId, String status, boolean promoteToFewshot) {
        if (!"HANDLED".equals(status) && !"IGNORED".equals(status)) {
            throw new IllegalArgumentException("非法的处理状态: " + status);
        }
        DataQueryFeedbackEntity feedback = feedbackMapper.selectById(feedbackId);
        if (feedback == null) {
            throw new IllegalArgumentException("反馈不存在: " + feedbackId);
        }
        feedback.setHandleStatus(status);
        feedbackMapper.updateById(feedback);

        // 只有"已处理"才沉淀示例; "忽略"意味着确认无需处理
        if ("HANDLED".equals(status) && promoteToFewshot && StringUtils.hasText(feedback.getCorrectSql())) {
            DataQueryLogEntity logEntity = queryLogMapper.selectOne(
                    new LambdaQueryWrapper<DataQueryLogEntity>()
                            .eq(DataQueryLogEntity::getBizId, feedback.getBizId()));
            if (logEntity != null) {
                DataFewshotEntity fewshot = new DataFewshotEntity();
                fewshot.setQuestion(logEntity.getQuestion());
                fewshot.setSqlText(feedback.getCorrectSql());
                fewshot.setTables(logEntity.getRecalledTables());
                fewshot.setExplanation(logEntity.getQuestion());
                fewshot.setEnabled(1);
                fewshot.setUseCount(0);
                fewshotMapper.insert(fewshot);
                log.info("[text2sql] bad case 已沉淀为 few-shot, feedbackId={}", feedbackId);
            }
        }
    }
}
