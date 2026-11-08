package com.lion.agent.text2sql.manage.service;

import com.lion.agent.common.PageResult;
import com.lion.agent.text2sql.entity.BizOrderEntity;

/**
 * 订单数据查询(只读浏览页)。
 * <p>
 * 订单是业务系统的数据, 问数平台只做查看, 不提供写入入口。
 */
public interface OrderQueryService {

    /**
     * 分页查询订单。
     *
     * @param keyword 模糊匹配订单号/下单用户/商品名称, 可空
     * @param city    下单城市精确过滤, 可空
     * @param status  订单状态精确过滤, 可空
     */
    PageResult<BizOrderEntity> page(int page, int size, String keyword, String city, Integer status);
}
