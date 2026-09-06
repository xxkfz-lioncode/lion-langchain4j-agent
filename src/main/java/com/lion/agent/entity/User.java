package com.lion.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统用户实体
 */
@Data
@TableName("sys_user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** 密码(BCrypt 密文存储, 单向哈希不可逆; 历史明文会在启动时自动升级) */
    private String password;

    private String nickname;

    private String avatar;

    /** 状态: 1 启用 / 0 禁用 */
    private Integer status;

    /** 角色: admin 超级管理员 / user 普通用户 */
    private String role;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
