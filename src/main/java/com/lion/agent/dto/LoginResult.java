package com.lion.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResult {

    /** Sa-Token 令牌(前端请求头 satoken 携带) */
    private String token;

    private Long userId;

    private String nickname;

    /** 角色: admin 超级管理员 / user 普通用户 */
    private String role;
}
