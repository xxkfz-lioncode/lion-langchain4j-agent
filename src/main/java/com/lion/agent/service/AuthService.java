package com.lion.agent.service;

import com.lion.agent.common.exception.BusinessException;
import com.lion.agent.pojo.dto.LoginRequest;
import com.lion.agent.pojo.dto.LoginResult;
import com.lion.agent.pojo.dto.RegisterRequest;

/**
 * 认证服务接口(登录 / 注册 / 登出 / 当前用户)
 */
public interface AuthService {

    /**
     * 登录: 校验用户名密码(BCrypt), 成功后签发 Sa-Token
     *
     * @throws BusinessException 用户名或密码错误
     */
    LoginResult login(LoginRequest request);

    /**
     * 注册: 用户名查重 + 密码 BCrypt 加密后入库
     *
     * @throws BusinessException 用户名已存在
     */
    void register(RegisterRequest request);

    /** 登出(当前登录会话下线) */
    void logout();

    /** 当前登录用户信息(未登录抛 NotLoginException) */
    LoginResult info();
}
