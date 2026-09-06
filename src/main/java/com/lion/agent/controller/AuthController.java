package com.lion.agent.controller;

import com.lion.agent.common.Result;
import com.lion.agent.dto.LoginRequest;
import com.lion.agent.dto.LoginResult;
import com.lion.agent.dto.RegisterRequest;
import com.lion.agent.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口(注册 / 登录 / 登出 / 当前用户信息)
 * <p>
 * Controller 仅做参数接收与结果返回, 业务校验均下沉到 {@link AuthService}。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 注册新账号(密码 BCrypt 加密存储)
     */
    @PostMapping("/register")
    public Result<Void> register(@RequestBody @Valid RegisterRequest request) {
        authService.register(request);
        return Result.ok();
    }

    /**
     * 登录: 校验用户名密码, 成功后签发 Sa-Token
     */
    @PostMapping("/login")
    public Result<LoginResult> login(@RequestBody @Valid LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    /**
     * 登出
     */
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.ok();
    }

    /**
     * 当前登录用户信息
     */
    @GetMapping("/info")
    public Result<LoginResult> info() {
        return Result.ok(authService.info());
    }
}
