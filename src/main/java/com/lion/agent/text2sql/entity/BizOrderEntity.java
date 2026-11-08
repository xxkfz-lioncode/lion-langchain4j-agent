package com.lion.agent.text2sql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单实体(问数演示目标表)。
 */
@Data
@TableName("biz_order")
public class BizOrderEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 订单编号 */
    private String orderNo;

    /** 下单用户 */
    private String userName;

    /** 联系电话 */
    private String phone;

    /** 商品名称 */
    private String productName;

    /** 商品类目 */
    private String category;

    /** 订单金额(元) */
    private BigDecimal amount;

    /** 购买数量 */
    private Integer quantity;

    /** 订单状态: 1=待付款 2=已付款 3=已发货 4=已完成 5=已取消 */
    private Integer status;

    /** 支付渠道 */
    private String payChannel;

    /** 下单城市 */
    private String city;

    /** 下单时间 */
    private LocalDateTime createdAt;
}
