package com.lion.agent.text2sql.manage.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lion.agent.common.PageResult;
import com.lion.agent.text2sql.entity.BizOrderEntity;
import com.lion.agent.text2sql.mapper.BizOrderMapper;
import com.lion.agent.text2sql.manage.service.OrderQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 订单只读查询实现。
 */
@Service
@RequiredArgsConstructor
public class OrderQueryServiceImpl implements OrderQueryService {

    private final BizOrderMapper orderMapper;

    @Override
    public PageResult<BizOrderEntity> page(int page, int size, String keyword, String city, Integer status) {
        Page<BizOrderEntity> p = orderMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<BizOrderEntity>()
                        // keyword: 订单号 / 下单用户 / 商品名称
                        .and(StringUtils.hasText(keyword), w -> w
                                .like(BizOrderEntity::getOrderNo, keyword)
                                .or().like(BizOrderEntity::getUserName, keyword)
                                .or().like(BizOrderEntity::getProductName, keyword))
                        .eq(StringUtils.hasText(city), BizOrderEntity::getCity, city)
                        .eq(status != null, BizOrderEntity::getStatus, status)
                        .orderByDesc(BizOrderEntity::getCreatedAt));
        return PageResult.of(p.getTotal(), p.getRecords());
    }
}
