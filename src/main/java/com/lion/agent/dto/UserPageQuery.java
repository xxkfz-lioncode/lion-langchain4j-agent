package com.lion.agent.dto;

import lombok.Data;

/**
 * 用户分页查询条件
 */
@Data
public class UserPageQuery {

    /** 页码(从 1 开始) */
    private Integer pageNum = 1;

    /** 每页大小 */
    private Integer pageSize = 10;

    /** 关键字: 用户名 / 昵称 模糊匹配 */
    private String keyword;
}
