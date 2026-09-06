package com.lion.agent.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.lion.agent.common.BusinessException;
import com.lion.agent.common.PageResult;
import com.lion.agent.common.Result;
import com.lion.agent.dto.UserCreateRequest;
import com.lion.agent.dto.UserPageQuery;
import com.lion.agent.dto.UserPasswordRequest;
import com.lion.agent.dto.UserStatusRequest;
import com.lion.agent.dto.UserUpdateRequest;
import com.lion.agent.dto.UserVO;
import com.lion.agent.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理接口(仅管理员): 列表 / 新增 / 编辑 / 删除 / 启用禁用 / 重置密码
 * <p>
 * 权限说明: 类级 {@code @SaCheckRole("admin")} 限定只有超级管理员可访问,
 * 角色由 {@link com.lion.agent.config.StpInterfaceImpl} 从 sys_user.role 读取。
 * <p>
 * 安全约束: 删除 / 禁用当前登录账号会被拒绝。
 */
@SaCheckRole("admin")
@RestController
@RequestMapping("/api/user")
public class UserManageController {

    private final UserService userService;

    public UserManageController(UserService userService) {
        this.userService = userService;
    }

    /** 用户分页列表(支持用户名/昵称模糊搜索) */
    @GetMapping("/page")
    public Result<PageResult<UserVO>> page(UserPageQuery query) {
        return Result.ok(userService.page(query));
    }

    /** 新增用户 */
    @PostMapping
    public Result<Void> create(@RequestBody @Valid UserCreateRequest request) {
        userService.create(request);
        return Result.ok();
    }

    /** 编辑用户(用户名/昵称/头像) */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable("id") Long id,
                               @RequestBody @Valid UserUpdateRequest request) {
        userService.update(id, request);
        return Result.ok();
    }

    /** 删除用户(不能删除自己) */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        if (id.equals(currentUserId())) {
            throw new BusinessException("不能删除当前登录账号");
        }
        userService.delete(id);
        return Result.ok();
    }

    /** 启用/禁用用户(不能禁用自己) */
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable("id") Long id,
                                     @RequestBody @Valid UserStatusRequest request) {
        if (request.getStatus() != null && request.getStatus() == 0 && id.equals(currentUserId())) {
            throw new BusinessException("不能禁用当前登录账号");
        }
        userService.updateStatus(id, request.getStatus());
        return Result.ok();
    }

    /** 重置密码 */
    @PutMapping("/{id}/password")
    public Result<Void> resetPassword(@PathVariable("id") Long id,
                                      @RequestBody @Valid UserPasswordRequest request) {
        userService.resetPassword(id, request.getPassword());
        return Result.ok();
    }

    private Long currentUserId() {
        return Long.valueOf(StpUtil.getLoginIdAsString());
    }
}
