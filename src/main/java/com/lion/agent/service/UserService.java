package com.lion.agent.service;

import com.lion.agent.common.PageResult;
import com.lion.agent.common.exception.BusinessException;
import com.lion.agent.pojo.dto.UserCreateRequest;
import com.lion.agent.pojo.dto.UserPageQuery;
import com.lion.agent.pojo.dto.UserUpdateRequest;
import com.lion.agent.pojo.dto.UserVO;
import com.lion.agent.pojo.entity.User;

/**
 * 用户服务接口(含登录/注册支撑与管理端维护)
 */
public interface UserService {

    /** 根据用户名查询用户(登录校验用) */
    User getByUsername(String username);

    /** 根据主键查询用户 */
    User getById(Long id);

    /**
     * 注册新用户: 校验用户名唯一后入库(密码须为已加密密文)
     *
     * @throws BusinessException 用户名已存在
     */
    void register(User user);

    // ---- 用户管理(管理端) ----

    /** 分页查询用户(支持用户名/昵称模糊搜索) */
    PageResult<UserVO> page(UserPageQuery query);

    /**
     * 新增用户(用户名唯一校验 + 密码 BCrypt 加密)
     *
     * @throws BusinessException 用户名已存在
     */
    void create(UserCreateRequest request);

    /**
     * 编辑用户(用户名唯一校验, 排除自身)
     *
     * @throws BusinessException 用户名已存在 / 用户不存在
     */
    void update(Long id, UserUpdateRequest request);

    /**
     * 删除用户
     *
     * @throws BusinessException 用户不存在
     */
    void delete(Long id);

    /**
     * 启用 / 禁用用户
     *
     * @throws BusinessException 用户不存在
     */
    void updateStatus(Long id, Integer status);

    /**
     * 重置密码(BCrypt 加密)
     *
     * @throws BusinessException 用户不存在
     */
    void resetPassword(Long id, String password);
}
