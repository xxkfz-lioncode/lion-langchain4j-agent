package com.lion.agent.config;

import cn.dev33.satoken.stp.StpInterface;
import com.lion.agent.pojo.entity.User;
import com.lion.agent.service.UserService;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Sa-Token 角色/权限数据源(自动注入)。
 * <p>
 * 从数据库 {@link User#getRole()} 读取当前登录用户的角色,
 * 供 {@code @SaCheckRole} / StpUtil.checkRole 使用。
 */
@Component
public class StpInterfaceImpl implements StpInterface {

    private final UserService userService;

    public StpInterfaceImpl(UserService userService) {
        this.userService = userService;
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        // 本工程未使用细粒度权限, 暂不返回
        return Collections.emptyList();
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Long userId = Long.valueOf(loginId.toString());
        User user = userService.getById(userId);
        String role = (user != null && "admin".equals(user.getRole())) ? "admin" : "user";
        return List.of(role);
    }
}
