package com.lion.agent.pojo.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户视图对象(管理端展示)
 */
@Data
public class UserVO {

    private Long id;

    private String username;

    private String nickname;

    private String avatar;

    /** 状态: 1 启用 / 0 禁用 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
