package com.lion.agent.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.lion.agent.common.exception.BusinessException;
import com.lion.agent.pojo.dto.LoginRequest;
import com.lion.agent.pojo.dto.LoginResult;
import com.lion.agent.pojo.dto.RegisterRequest;
import com.lion.agent.pojo.entity.User;
import com.lion.agent.service.AuthService;
import com.lion.agent.service.UserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 认证服务实现
 * <p>
 * 密码一律 BCrypt 存储与校验(单向哈希, 不可反推明文),
 * 登录态由 Sa-Token 管理(token 签发 / 校验 / 登出)。
 */
@Service
public class AuthServiceImpl implements AuthService {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    public AuthServiceImpl(UserService userService, PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public LoginResult login(LoginRequest request) {
        User user = userService.getByUsername(request.getUsername());
        // 用户不存在与密码错误返回同一提示, 避免账号探测
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }
        StpUtil.login(user.getId());
        return new LoginResult(StpUtil.getTokenValue(), user.getId(), user.getNickname(), user.getRole());
    }

    @Override
    public void register(RegisterRequest request) {
        User user = new User();
        user.setUsername(request.getUsername());
        // 只存 BCrypt 哈希, 不落明文
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(StringUtils.hasText(request.getNickname())
                ? request.getNickname() : request.getUsername());
        // 注册用户默认普通角色
        user.setRole("user");
        userService.register(user);
    }

    @Override
    public void logout() {
        StpUtil.logout();
    }

    @Override
    public LoginResult info() {
        Long userId = StpUtil.getLoginIdAsLong();
        User user = userService.getById(userId);
        return new LoginResult(StpUtil.getTokenValue(), user.getId(), user.getNickname(), user.getRole());
    }
}
